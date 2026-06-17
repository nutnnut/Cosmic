package server.bots;

import client.Character;
import client.inventory.Equip;
import client.inventory.InventoryType;
import client.inventory.Item;
import constants.game.GameConstants;
import constants.id.ItemId;
import constants.inventory.ItemConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.bots.BotGrindPlanner.GearProspect;
import server.bots.BotGrindPlanner.MobCandidate;
import server.bots.BotGrindPlanner.Recommendation;
import server.life.LifeFactory;
import server.life.Monster;
import server.life.MonsterInformationProvider;
import server.maps.MapFactory;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.concurrent.ThreadLocalRandom;

/**
 * "Where should we grind?" — builds real candidates for {@link BotGrindPlanner} from the bot's own
 * state and world data, then explains the pick in chat. Decision + reasoning only: the bot does NOT
 * travel anywhere (bots are owner-tethered companions; travel is a later slice).
 *
 * <p>Candidate = one MAP: every grindable mob on it blended by spawn share (time killing one mob
 * is time not killing another, so exp, kill time and drop chances are all spawn-share weighted —
 * see {@link #blendCandidate}), labeled with the dominant mob. Kill time uses the combat SSOT
 * ({@code estimateBestSkillHitDamage},
 * physical fallback) — the same model as the scroll farming-cost anchor. Gear prospects = equip
 * drops ({@code drop_data}) that this bot can wear, valued as the EXPECTED IMPROVEMENT over the
 * worn item in that slot: Monte Carlo over the real drop-roll distribution (the same
 * {@code ii.randomizeStats} call the map drop path uses — vanilla spread plus the godly mixture),
 * {@code E[max(0, score(roll) - score(worn))]}. A below-average worn roll keeps re-farming the
 * same item moderately valuable; a near-godly worn roll drives it to ~zero; an empty slot is
 * worth the full mean — so stay-or-leave emerges from the planner's gear-first shortlist plus
 * the travel penalty, with no drop counters or stay timers anywhere. Both sides of the
 * comparison score {@link BotScrollManager#potentialValue} (offense + discounted scroll
 * headroom on remaining upgrade slots), so a maxed-out worn item can lose to a weaker drop
 * that still scrolls higher — with the per-slot value priced from the scroll catalog per
 * equip type, not flat.
 *
 * <p>Equip SCROLL drops are gear prospects too (gear progression isn't only new items): one
 * scroll is worth {@code effective success x stat value} through the same offense SSOT (att
 * outweighs main stat; matk is worthless to non-mages), and only counts while the bot wears
 * something it applies to with an open upgrade slot — see {@link #scrollExpectedGain}. This
 * values the LOOT for planning only; actually using scrolls stays behind the owner-confirmed
 * self-scrolling command.
 *
 * <p>Skipped on purpose (documented, not silent): towns, instanced/event fields (mapId &ge;
 * 900000000), maps with fewer than {@link #MIN_SPAWN_POINTS} grindable spawn points, bosses,
 * friendlies, 0-exp props, and {@code drop_data_global} (it adds the same items to every mob,
 * washing out map differentiation).
 */
final class BotGrindAdvisor {

    private static final Logger log = LoggerFactory.getLogger(BotGrindAdvisor.class);

    /** Same producer anchors as the scroll farming-cost model. */
    private static final double ATTACK_CYCLE_SECONDS = 0.72;
    private static final double DROP_CHANCE_DENOMINATOR = 1_000_000.0;
    private static final int MIN_SPAWN_POINTS = 3;
    private static final int INSTANCED_MAPID_FLOOR = 900000000;
    /** Ignore "upgrades" below this offense-score gain — rounding noise, not progression. */
    private static final double MIN_GEAR_GAIN_SCORE = 0.5;
    /** Level-gated gear (drop or bagged) still counts this many levels ahead, decayed per
     *  level to go — wearable-now beats wearable-later, smoothly. */
    private static final int GEAR_LEVEL_HORIZON = 10;
    private static final double LEVEL_WAIT_DECAY = 0.9;
    /** Monte Carlo drop rolls per item per pass (~microseconds each; runs on DECIDE_POOL). */
    private static final int ROLL_SAMPLES = 32;

    /** Lazily-loaded gear-progression drops per mob (equips + equip scrolls): mobId → {itemId, chance}. */
    private static volatile Map<Integer, List<int[]>> gearDropsByMob;

    private BotGrindAdvisor() {}

    /**
     * Every heavy advisor pass runs here, NEVER on the bot tick threads or the shared
     * TimerManager pool: the first pass builds the spawn index + world graph (~30s of WZ
     * scanning) and even warm passes iterate every known mob — on a game thread that reads
     * as a server freeze. Single thread also serializes a party's member passes.
     */
    static final java.util.concurrent.ExecutorService DECIDE_POOL =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "bot-grind-advisor");
                t.setDaemon(true);
                return t;
            });

    private static volatile boolean cachesWarmed = false;

    /** Pre-build the WZ-derived caches off-thread so the first real decision doesn't pay them. */
    static void warmCachesAsync() {
        if (cachesWarmed) {
            return;
        }
        cachesWarmed = true;
        // One daemon thread per cache: each warmup is seconds of WZ scanning on a cold boot,
        // so neither should wait on the other or queue ahead of real passes on DECIDE_POOL.
        Thread spawnWarmup = new Thread(BotSpawnIndex::get, "bot-spawn-index-warmup");
        spawnWarmup.setDaemon(true);
        spawnWarmup.start();
        Thread worldWarmup = new Thread(BotWorldGraph::get, "bot-world-graph-warmup");
        worldWarmup.setDaemon(true);
        worldWarmup.start();
        // Mob stats, gear-drop table, and the ii equip/scroll catalog: the cold cache tax the first
        // decision would otherwise pay on the single DECIDE_POOL thread (measured full-world cold
        // ~16.5s -> warm ~0.26s; the BULK is ii equip/scroll-catalog parsing, mob loads ~1.5s of it).
        // MIN_PRIORITY so it never steals CPU from boot or live ticks; depends on the spawn index.
        Thread dataWarmup = new Thread(BotGrindAdvisor::warmGrindData, "bot-grind-data-warmup");
        dataWarmup.setDaemon(true);
        dataWarmup.setPriority(Thread.MIN_PRIORITY);
        dataWarmup.start();
    }

    /**
     * Pre-load the bot-independent grind caches off-thread at boot so the first decision pass
     * doesn't pay the cold cache tax on the single {@link #DECIDE_POOL} thread. Best-effort and
     * idempotent: it loads, early, the same data the decision would otherwise lazy-load — per-mob
     * stats ({@link LifeFactory}) plus the gear-drop table and the {@link ItemInformationProvider}
     * equip/scroll catalog (which measured as the BULK of the cold cost, not the mob loads).
     * Safe to run alongside live map spawns: the maps it warms ({@code LifeFactory.monsterStats}
     * and the {@code MonsterInformationProvider} mob-attack caches) are {@code ConcurrentHashMap}.
     * Also pre-warms the {@code ItemInformationProvider} equip catalog (the dominant cold tax for the
     * gear scan); the ii caches it touches on that path are {@code ConcurrentHashMap} so the warm is
     * safe alongside game-thread reads/writes.
     */
    static void warmGrindData() {
        try {
            MonsterInformationProvider mi = MonsterInformationProvider.getInstance();
            gearDropsByMob(); // drop_data equips/scrolls: one DB query, cached for the gear scan
            BotSpawnIndex.Index index = BotSpawnIndex.get(); // blocks until the spawn scan is built
            java.util.Set<Integer> mobIds = new java.util.HashSet<>();
            for (BotSpawnIndex.MapSpawns map : index.byMap().values()) {
                mobIds.addAll(map.mobCounts().keySet());
                // mapName -> MapFactory.loadPlaceName walks String.wz per call; the build loop hits it
                // once per qualifying map. mapNameCache is a ConcurrentHashMap, so warming it is safe.
                mapName(map.mapId());
            }
            int warmed = 0;
            for (int mobId : mobIds) {
                try {
                    if (LifeFactory.getMonster(mobId) != null) {
                        warmed++;
                    }
                    // The build loop also looks up each mob's name (a String.wz read on first touch);
                    // mobNameCache is a ConcurrentHashMap so warming it off-thread is safe.
                    mi.getMobNameFromId(mobId);
                } catch (RuntimeException ignored) {
                    // a bad mob id just stays lazy — never fail the whole warmup over one mob
                }
            }
            log.info("Bot grind cache warmup: loaded {} of {} grindable mob stats", warmed, mobIds.size());

            // Pre-warm the ii WZ equip catalog the gear scan touches, off-thread, so the first grind
            // decision doesn't pay the cold equip-stat parse on the decide thread. Droppable gear only:
            // getEquipById per id is a ~7ms WZ parse, so warming the whole ~10k equip catalog would cost
            // ~80s — not worth it. Bagged gear the bot already owns still lazy-loads on first decision.
            ItemInformationProvider ii = ItemInformationProvider.getInstance();
            // The dominant cold cost of the first gear scan is building the catalog-scroll index
            // (parses every scroll's stats/reqs once). Prime it here via the shared SSOT builder.
            BotScrollManager.warmScrollCatalog(ii);
            int equipsWarmed = 0;
            int scrollsWarmed = 0;
            for (List<int[]> drops : gearDropsByMob().values()) {
                for (int[] drop : drops) {
                    int id = drop[0];
                    try {
                        if (id >= 1_000_000 && id <= 1_999_999) {
                            // The gear scan walks the (uncached) WZ item directory once per equip
                            // through these three: getEquipById (stats + untradeable via its stat
                            // loop), getEquipmentSlot (primarySlot), getEquipLevelReq (wearable check).
                            ii.getEquipById(id);
                            ii.getEquipmentSlot(id);
                            ii.getEquipLevelReq(id);
                            equipsWarmed++;
                        } else if (id >= 2_040_000 && id <= 2_049_999) {
                            ii.getEquipStats(id);  // scrolls: getEquipById is equip-only
                            scrollsWarmed++;
                        }
                    } catch (RuntimeException ignored) {
                        // one corrupt id must not abort the warm — it just stays lazy
                    }
                }
            }
            log.info("Bot grind cache warmup: warmed {} equip + {} scroll catalog entries (droppable set)",
                    equipsWarmed, scrollsWarmed);
        } catch (RuntimeException e) {
            log.warn("Bot grind cache warmup failed; decisions will lazy-load as before", e);
        }
    }

    /** Owner asked where to grind: decide (off-thread), then explain the what/why in chat. */
    static void requestGrindAdvice(BotEntry entry, Character bot) {
        if (entry == null || bot == null) {
            return;
        }
        DECIDE_POOL.execute(() -> {
            Recommendation rec;
            try {
                rec = recommend(entry, bot);
            } catch (RuntimeException e) {
                log.warn("Grind advice failed for {}", bot.getName(), e);
                BotManager.getInstance().botReply(entry, "hmm, can't think of a good spot rn");
                return;
            }
            if (rec == null) {
                BotManager.getInstance().botReply(entry, "honestly nowhere looks worth it for me rn");
                return;
            }
            List<String> lines = composeAdvice(rec);
            BotManager.getInstance().botReply(entry, lines.get(0));
            if (lines.size() > 1) {
                BotManager.after(BotManager.randMs(700, 1100),
                        () -> BotManager.getInstance().botReply(entry, lines.get(1)));
            }
        });
    }

    /** Full decision pass over the world. Heavy-ish on first call (WZ mob loads); fine async. */
    static Recommendation recommend(BotEntry entry, Character bot) {
        return recommend(entry, bot, mapId -> true);
    }

    /** Decision pass restricted to allowed maps (autopilot: only maps the bot can walk to). */
    static Recommendation recommend(BotEntry entry, Character bot, java.util.function.IntPredicate mapAllowed) {
        return recommend(entry, bot, mapAllowed, mapId -> 1.0);
    }

    /** Like {@link #recommend(BotEntry, Character, java.util.function.IntPredicate)} with a
     *  per-map score weight (autopilot travel-time penalty, see {@link BotTravelCost}). */
    static Recommendation recommend(BotEntry entry, Character bot,
                                    java.util.function.IntPredicate mapAllowed,
                                    java.util.function.IntToDoubleFunction mapScoreWeight) {
        return recommend(entry, bot, mapAllowed, mapScoreWeight, mapId -> 0.0);
    }

    /** Like the 4-arg {@code recommend} plus a per-map crowd surcharge: maps with other bots/players
     *  already there yield fewer kills/h (spawn-shared), so the bot disperses instead of stacking. */
    static Recommendation recommend(BotEntry entry, Character bot,
                                    java.util.function.IntPredicate mapAllowed,
                                    java.util.function.IntToDoubleFunction mapScoreWeight,
                                    java.util.function.IntToDoubleFunction extraCompetitors) {
        List<MobCandidate> candidates = buildCandidates(entry, bot, mapAllowed);
        return BotGrindPlanner.planBest(candidates, mapScoreWeight, extraCompetitors, ThreadLocalRandom.current());
    }

    /** Candidate pool for external planners (party autopilot). Same pool recommend() uses;
     *  pass the allowed-map filter IN so unreachable maps skip the per-mob profiling cost. */
    static List<MobCandidate> candidatesFor(BotEntry entry, Character bot,
                                            java.util.function.IntPredicate mapAllowed) {
        return buildCandidates(entry, bot, mapAllowed);
    }

    private static List<MobCandidate> buildCandidates(BotEntry entry, Character bot,
                                                      java.util.function.IntPredicate mapAllowed) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        BotSpawnIndex.Index index = BotSpawnIndex.get();
        MonsterInformationProvider mi = MonsterInformationProvider.getInstance();

        double totalWornOffense = totalWornOffense(bot, ii);
        Map<Short, Double> wornScoreBySlot = new HashMap<>();
        Map<Integer, double[]> rollScoreCache = new HashMap<>(); // per pass: same item drops from many mobs
        Map<Integer, Double> scrollGainCache = new HashMap<>();
        Map<Integer, Optional<MobProfile>> profiles = new HashMap<>();

        long tBuild = BotPerformanceMonitor.start();
        List<MobCandidate> candidates = new ArrayList<>();
        for (BotSpawnIndex.MapSpawns map : index.byMap().values()) {
            if (map.town() || map.mapId() >= INSTANCED_MAPID_FLOOR
                    || !mapAllowed.test(map.mapId())) {
                continue;
            }
            Map<MobProfile, Integer> pointsByMob = new HashMap<>();
            for (Map.Entry<Integer, Integer> e : map.mobCounts().entrySet()) {
                MobProfile p = profiles.computeIfAbsent(e.getKey(),
                        id -> Optional.ofNullable(profileFor(entry, bot, ii, mi, id, true,
                                wornScoreBySlot, rollScoreCache, scrollGainCache, totalWornOffense)))
                        .orElse(null);
                if (p != null && p.exp() > 0) { // 0-exp props aren't grinding
                    pointsByMob.put(p, e.getValue());
                }
            }
            if (totalPoints(pointsByMob) < MIN_SPAWN_POINTS) {
                continue;
            }
            long tBlend = BotPerformanceMonitor.start();
            candidates.add(blendCandidate(map.mapId(), mapName(map.mapId()), map.areaPx(),
                    pointsByMob));
            BotPerformanceMonitor.recordSince("grind.blend", tBlend);
        }
        BotPerformanceMonitor.recordSince("grind.build", tBuild);
        return candidates;
    }

    /** A mob's bot-specific grind numbers, computed once per pass. Exp is rate-multiplied;
     *  prospect chances are per kill OF THIS MOB (the map blend dilutes them by spawn share). */
    record MobProfile(int mobId, String mobName, int level, int exp, double killSeconds,
                      List<GearProspect> prospects) {}

    /**
     * The bot's blended grind exp-per-minute on the map it is currently standing on — the
     * baseline the quest advisor ({@link BotQuestScorer}) measures a quest against. CHEAP on
     * purpose: it profiles ONLY the current map's mobs and ONLY their exp + kill time
     * (no gear prospects, no {@code drop_data} DB hit), so it is safe to call on the bot tick
     * thread (the auto-suggest path). Returns base (un-rated) exp/min — the same raw units
     * {@code BotQuestManager.mobExp} reports, so the rate cancels in the value/cost ratio.
     * 0 when the current map has no grindable mobs (a town, an event field, or unknown).
     */
    static double currentMapExpPerMinute(BotEntry entry, Character bot) {
        if (bot == null || bot.getMap() == null) {
            return 0.0;
        }
        int mapId = bot.getMapId();
        BotSpawnIndex.MapSpawns map = BotSpawnIndex.get().byMap().get(mapId);
        if (map == null || map.town() || mapId >= INSTANCED_MAPID_FLOOR) {
            return 0.0;
        }
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        MonsterInformationProvider mi = MonsterInformationProvider.getInstance();
        Map<MobProfile, Integer> pointsByMob = new HashMap<>();
        for (Map.Entry<Integer, Integer> e : map.mobCounts().entrySet()) {
            // withProspects=false: no gear-drop valuation, no DB — just exp + kill time.
            MobProfile p = profileFor(entry, bot, ii, mi, e.getKey(), false, null, null, null, 0.0);
            if (p != null && p.exp() > 0) {
                pointsByMob.put(p, e.getValue());
            }
        }
        if (totalPoints(pointsByMob) < MIN_SPAWN_POINTS) {
            return 0.0;
        }
        MobCandidate blend = blendCandidate(mapId, "", map.areaPx(), pointsByMob);
        // exp() here is rate-multiplied (profileFor applies bot.getExpRate()); divide it back out
        // so the baseline is in BASE exp units, matching BotQuestManager.mobExp (un-rated).
        double rate = Math.max(1.0, bot.getExpRate());
        return blend.exp() * BotGrindPlanner.killsPerHour(blend) / 60.0 / rate;
    }

    /**
     * The best grind rate ACHIEVABLE by this bot, in base exp/min — the opportunity-cost baseline
     * for quest scoring when the bot is asked off its grind map (in town / in transit), where
     * {@link #currentMapExpPerMinute} reads 0 and would otherwise make leaving to quest look free.
     * Reuses the full grind decision ({@link #recommend}) so it is level-appropriate by
     * construction. Heavier than the current-map read (evaluates candidate maps); call off-thread.
     * 0 when the bot has no grindable candidate at all.
     */
    static double bestGrindExpPerMinute(BotEntry entry, Character bot) {
        if (bot == null) {
            return 0.0;
        }
        BotGrindPlanner.Recommendation rec = recommend(entry, bot);
        if (rec == null) {
            return 0.0;
        }
        // expPerHour is rate-multiplied (BotGrindPlanner: candidate.exp() carries bot.getExpRate());
        // divide it back out for base exp/min, matching currentMapExpPerMinute / mobExp units.
        double rate = Math.max(1.0, bot.getExpRate());
        return rec.expPerHour() / 60.0 / rate;
    }

    /** Null = not grindable for this bot: boss/friendly, unresolvable, or the bot can't
     *  meaningfully damage it (such mobs don't dilute a map — the bot won't engage them). */
    private static MobProfile profileFor(BotEntry entry, Character bot, ItemInformationProvider ii,
                                         MonsterInformationProvider mi, int mobId, boolean withProspects,
                                         Map<Short, Double> wornScoreBySlot,
                                         Map<Integer, double[]> rollScoreCache,
                                         Map<Integer, Double> scrollGainCache,
                                         double totalWornOffense) {
        Monster mob;
        try {
            mob = LifeFactory.getMonster(mobId);
        } catch (RuntimeException ex) {
            return null;
        }
        if (mob == null || mob.getStats() == null) {
            return null;
        }
        var stats = mob.getStats();
        if (stats.isBoss() || stats.isFriendly()) {
            return null;
        }
        long tKill = BotPerformanceMonitor.start();
        double killSeconds = killSeconds(entry, bot, mob);
        BotPerformanceMonitor.recordSince("grind.kill", tKill);
        if (killSeconds <= 0) {
            return null;
        }
        long tGear = BotPerformanceMonitor.start();
        List<GearProspect> gear = withProspects
                ? gearProspects(bot, ii, mobId, wornScoreBySlot, rollScoreCache, scrollGainCache,
                        totalWornOffense)
                : List.of();
        BotPerformanceMonitor.recordSince("grind.gear", tGear);
        return new MobProfile(mobId, mobName(mi, mobId), stats.getLevel(),
                stats.getExp() * bot.getExpRate(), killSeconds, gear);
    }

    /**
     * Spawn-share blend: the bot kills what it encounters, so time on one mob is time not on
     * another. A map's kill cycle and exp are each mob's numbers weighted by its share of the
     * map's spawn points (a great-exp mob mixed with a poor one lands in between), and every
     * drop chance dilutes by its dropper's share (summed when several mobs drop the same item).
     * The candidate is labeled with the dominant mob (most spawn points; exp breaks ties).
     */
    static MobCandidate blendCandidate(int mapId, String mapName, int mapAreaPx,
                                       Map<MobProfile, Integer> pointsByMob) {
        int totalPoints = totalPoints(pointsByMob);
        double killSeconds = 0.0;
        double exp = 0.0;
        MobProfile face = null;
        int facePoints = -1;
        Map<Integer, GearProspect> gearByItem = new LinkedHashMap<>();
        for (Map.Entry<MobProfile, Integer> e : pointsByMob.entrySet()) {
            MobProfile p = e.getKey();
            double share = e.getValue() / (double) totalPoints;
            killSeconds += share * p.killSeconds();
            exp += share * p.exp();
            for (GearProspect g : p.prospects()) {
                GearProspect diluted = new GearProspect(g.itemId(), g.itemName(),
                        g.chancePerKill() * share, g.scoreGain(), g.dpsGainFraction());
                gearByItem.merge(g.itemId(), diluted, (a, b) -> new GearProspect(a.itemId(),
                        a.itemName(), a.chancePerKill() + b.chancePerKill(), a.scoreGain(),
                        a.dpsGainFraction()));
            }
            if (e.getValue() > facePoints
                    || (e.getValue() == facePoints && p.exp() > face.exp())) {
                face = p;
                facePoints = e.getValue();
            }
        }
        return new MobCandidate(face.mobId(), face.mobName(), face.level(),
                (int) Math.round(exp), killSeconds, mapId, mapName, totalPoints, mapAreaPx,
                List.copyOf(gearByItem.values()));
    }

    private static int totalPoints(Map<MobProfile, Integer> pointsByMob) {
        int total = 0;
        for (int pts : pointsByMob.values()) {
            total += pts;
        }
        return total;
    }

    /**
     * "farm &lt;item&gt;": candidates are every allowed map where a dropper spawns, with the
     * same spawn-share blend as grinding — mobs that don't drop the item still take kill time,
     * so they dilute the map's items/hour. Handed to {@link BotGrindPlanner#planFarmBest} which
     * scores purely by expected items/hour. Null when nothing the bot can reach (and damage)
     * drops it.
     */
    static Recommendation recommendFarmItem(BotEntry entry, Character bot, int itemId,
                                            java.util.function.IntPredicate mapAllowed) {
        return recommendFarmItem(entry, bot, itemId, mapAllowed, mapId -> 1.0);
    }

    /** Like {@link #recommendFarmItem(BotEntry, Character, int, java.util.function.IntPredicate)}
     *  with a per-map score weight (autopilot travel-time penalty). */
    static Recommendation recommendFarmItem(BotEntry entry, Character bot, int itemId,
                                            java.util.function.IntPredicate mapAllowed,
                                            java.util.function.IntToDoubleFunction mapScoreWeight) {
        return recommendFarmItem(entry, bot, itemId, mapAllowed, mapScoreWeight, mapId -> 0.0);
    }

    /** Like the 5-arg {@code recommendFarmItem} plus a per-map crowd surcharge (anti-stacking). */
    static Recommendation recommendFarmItem(BotEntry entry, Character bot, int itemId,
                                            java.util.function.IntPredicate mapAllowed,
                                            java.util.function.IntToDoubleFunction mapScoreWeight,
                                            java.util.function.IntToDoubleFunction extraCompetitors) {
        Map<Integer, Integer> droppers = droppersForItem.droppers(itemId);
        if (droppers.isEmpty()) {
            return null;
        }
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        MonsterInformationProvider mi = MonsterInformationProvider.getInstance();
        BotSpawnIndex.Index index = BotSpawnIndex.get();
        String name = itemName(ii, itemId);

        Map<Integer, Optional<MobProfile>> profiles = new HashMap<>();
        Set<Integer> dropperMaps = new LinkedHashSet<>();
        for (int mobId : droppers.keySet()) {
            for (BotSpawnIndex.SpawnSite site : BotSpawnIndex.spawnSites(mobId)) {
                dropperMaps.add(site.mapId());
            }
        }

        List<MobCandidate> candidates = new ArrayList<>();
        for (int mapId : dropperMaps) {
            BotSpawnIndex.MapSpawns map = index.byMap().get(mapId);
            if (map == null || map.town() || mapId >= INSTANCED_MAPID_FLOOR
                    || !mapAllowed.test(mapId)) {
                continue;
            }
            Map<MobProfile, Integer> pointsByMob = new HashMap<>();
            for (Map.Entry<Integer, Integer> e : map.mobCounts().entrySet()) {
                MobProfile p = profiles.computeIfAbsent(e.getKey(),
                        id -> Optional.ofNullable(profileFor(entry, bot, ii, mi, id, false,
                                null, null, null, 0.0)))
                        .orElse(null);
                // Grindable mobs dilute; 0-exp droppers (prop-like sources) still count.
                if (p != null && (p.exp() > 0 || droppers.containsKey(p.mobId()))) {
                    pointsByMob.put(p, e.getValue());
                }
            }
            int totalPoints = totalPoints(pointsByMob);
            if (totalPoints < MIN_SPAWN_POINTS) {
                continue;
            }
            // The map's item rate: each dropper's per-kill chance diluted by its spawn share.
            double chancePerKill = 0.0;
            MobProfile face = null;
            double faceRate = 0.0;
            for (Map.Entry<MobProfile, Integer> e : pointsByMob.entrySet()) {
                Integer chance = droppers.get(e.getKey().mobId());
                if (chance == null) {
                    continue;
                }
                double share = e.getValue() / (double) totalPoints;
                double rate = share * Math.min(1.0,
                        chance * bot.getDropRate() / DROP_CHANCE_DENOMINATOR);
                chancePerKill += rate;
                if (rate > faceRate) {
                    faceRate = rate;
                    face = e.getKey();
                }
            }
            if (face == null || chancePerKill <= 0) {
                continue;
            }
            MobCandidate blend = blendCandidate(mapId, mapName(mapId), map.areaPx(), pointsByMob);
            candidates.add(new MobCandidate(face.mobId(), face.mobName(), face.level(),
                    blend.exp(), blend.killSeconds(), mapId, blend.mapName(), totalPoints,
                    map.areaPx(), List.of(new GearProspect(itemId, name, chancePerKill, 0, 0))));
        }
        return BotGrindPlanner.planFarmBest(candidates, mapScoreWeight, extraCompetitors, ThreadLocalRandom.current());
    }

    /** All mobs dropping an item with their best chance ({@code drop_data}); test seam. */
    @FunctionalInterface
    interface DroppersLookup {
        Map<Integer, Integer> droppers(int itemId);
    }

    static DroppersLookup droppersForItem = BotGrindAdvisor::queryDroppers;

    private static Map<Integer, Integer> queryDroppers(int itemId) {
        Map<Integer, Integer> droppers = new HashMap<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT dropperid, MAX(chance) AS chance FROM drop_data"
                             + " WHERE itemid = ? AND chance > 0 GROUP BY dropperid")) {
            ps.setInt(1, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    droppers.put(rs.getInt("dropperid"), rs.getInt("chance"));
                }
            }
        } catch (SQLException e) {
            log.warn("Couldn't load droppers for item {}", itemId, e);
        }
        return droppers;
    }

    /** Time to kill one mob for THIS bot — same shape as the scroll farming-cost producer model. */
    private static double killSeconds(BotEntry entry, Character bot, Monster mob) {
        double perAttack = BotCombatManager.estimateBestSkillHitDamage(entry, bot, mob);
        if (perAttack <= 0.0) {
            int mobWdef = mob.getStats() != null ? mob.getStats().getPDDamage() : 0;
            perAttack = BotEquipManager.expectedDamageAfterDef(
                    bot.calculateMaxBaseDamage(bot.getTotalWatk()), mobWdef);
        }
        if (perAttack <= 0.0) {
            return -1;
        }
        double dps = perAttack / ATTACK_CYCLE_SECONDS;
        // Factor accuracy: a missed swing deals no damage, so a high-avoid mob the bot can barely hit
        // takes proportionally longer to kill. This makes the planner prefer maps the bot can actually
        // land hits on (a low-DEX warrior steers away from high-avoid mobs) and keeps exp/hr honest.
        // Magic attackers use magic accuracy (INT/LUK), so don't penalize a mage on its low DEX.
        boolean magic = bot.getJobStyle() == client.Job.MAGICIAN;
        double hitChance = server.combat.CombatFormulaProvider.getInstance().calculateMobHitChance(bot, mob, magic);
        double effectiveDps = dps * Math.max(0.01, hitChance);
        return Math.max(ATTACK_CYCLE_SECONDS, Math.max(1, mob.getMaxHp()) / effectiveDps);
    }

    /** Gear-progression drops of this mob: wearable equips valued as expected improvement over
     *  the worn item, equip scrolls valued by {@link #scrollExpectedGain}. */
    private static List<GearProspect> gearProspects(Character bot, ItemInformationProvider ii, int mobId,
                                                    Map<Short, Double> wornScoreBySlot,
                                                    Map<Integer, double[]> rollScoreCache,
                                                    Map<Integer, Double> scrollGainCache,
                                                    double totalWornOffense) {
        List<int[]> drops = gearDropsByMob().get(mobId);
        if (drops == null) {
            return List.of();
        }
        List<GearProspect> out = new ArrayList<>(2);
        for (int[] drop : drops) {
            int itemId = drop[0];
            double gain = itemId / 10000 == BotScrollManager.SCROLL_ITEM_PREFIX
                    ? scrollGainCache.computeIfAbsent(itemId, id -> scrollGains.gain(bot, id))
                    : equipGain(bot, ii, itemId, wornScoreBySlot, rollScoreCache);
            if (gain < MIN_GEAR_GAIN_SCORE) {
                continue;
            }
            double chancePerKill = Math.min(1.0,
                    drop[1] * (double) bot.getDropRate() / DROP_CHANCE_DENOMINATOR);
            out.add(new GearProspect(itemId, itemName(ii, itemId), chancePerKill,
                    gain, gain / Math.max(1.0, totalWornOffense)));
        }
        return out;
    }

    /** Expected improvement of one more drop roll of this equip over the best the bot already
     *  OWNS for the slot (worn or bagged), both sides discounted by how long until wearable.
     *  Delegates to the shared {@link #expectedAcquireGain} SSOT with the drop roll sampler,
     *  memoized per item id across the mob's drop list. */
    private static double equipGain(Character bot, ItemInformationProvider ii, int itemId,
                                    Map<Short, Double> wornScoreBySlot,
                                    Map<Integer, double[]> rollScoreCache) {
        return expectedAcquireGain(bot, ii, itemId,
                (b, id, n) -> rollScoreCache.computeIfAbsent(id, k -> rollScores.sample(b, k, n)),
                ROLL_SAMPLES, wornScoreBySlot);
    }

    /**
     * SSOT expected-acquire gain: the expected improvement of obtaining {@code itemId} — rolled by
     * {@code sampler} ({@code sampleCount} rolls) — over the best the bot already OWNS for the slot
     * (worn or bagged, future copies discounted by levels-until-wearable). Shared by mob-drop
     * farming ({@link #equipGain}), Maker crafting, and gachapon so all three rank equips on ONE
     * scale: the caller just supplies a sampler matching the acquisition source (drop / maker /
     * gacha pull). Returns 0 for non-equips or requirements the bot can never grow into. The sampler
     * is invoked only after the item validates, so callers may sample lazily/expensively.
     * {@code ownedBarCache} memoizes per-slot baselines across a batch (pass a fresh map for one-off).
     */
    static double expectedAcquireGain(Character bot, ItemInformationProvider ii, int itemId,
                                      RollScoreSampler sampler, int sampleCount,
                                      Map<Short, Double> ownedBarCache) {
        if (ii.getEquipStats(itemId) == null) {
            return 0.0;
        }
        Short slot = BotScrollManager.primarySlot(ii, itemId);
        if (slot == null) {
            return 0.0;
        }
        Item catalog;
        try {
            catalog = ii.getEquipById(itemId);
        } catch (RuntimeException ex) {
            return 0.0;
        }
        if (!(catalog instanceof Equip eq)) {
            return 0.0;
        }
        int levelsToGo = BotScrollManager.levelsUntilWearable(bot, ii, eq, GEAR_LEVEL_HORIZON);
        if (levelsToGo < 0) {
            return 0.0; // unmet stat/job requirement is never grown into (low-secondary builds)
        }
        long tBar = BotPerformanceMonitor.start();
        double ownedScore = gearBar(bot, ii, itemId, slot, ownedBarCache);
        BotPerformanceMonitor.recordSince("grind.ownedbar", tBar);
        double[] samples = sampler.sample(bot, itemId, sampleCount);
        return expectedImprovement(samples, levelDiscount(levelsToGo), ownedScore);
    }

    /** Value of waiting: a thing usable in {@code levelsToGo} levels is worth a decayed
     *  fraction of itself today (1.0 when wearable now). */
    static double levelDiscount(int levelsToGo) {
        return Math.pow(LEVEL_WAIT_DECAY, Math.max(0, levelsToGo));
    }

    // Synthetic wornScoreBySlot cache keys for ensemble pieces (real slots are negative).
    private static final short KEY_BEST_TOP = 105;
    private static final short KEY_BEST_OVERALL = 106;
    private static final short KEY_BEST_1H_WEAPON = 111;
    private static final short KEY_BEST_2H_WEAPON = 112;

    /**
     * The score a candidate drop must beat, honoring the optimizer's cross-slot exclusivity
     * (2H weapon ↔ shield, overall ↔ top+pants — see BotEquipManager.solveForWeapon): the bar
     * is the best ENSEMBLE the bot already owns for the family minus the partner piece the
     * candidate keeps. A pants drop while a strong overall is worn must beat
     * (ensemble - best top), not an empty pants slot; a shield drop on a 2H build competes
     * against the weapon itself (and is hard-gated by levelsUntilWearable anyway).
     */
    private static double gearBar(Character bot, ItemInformationProvider ii, int itemId, short slot,
                                  Map<Short, Double> cache) {
        switch (slot) {
            case -5, -6 -> {
                double top = cache.computeIfAbsent(KEY_BEST_TOP,
                        k -> bestOwnedScore(bot, ii, (short) -5, id -> !ItemConstants.isOverall(id)));
                double overall = cache.computeIfAbsent(KEY_BEST_OVERALL,
                        k -> bestOwnedScore(bot, ii, (short) -5, ItemConstants::isOverall));
                double pants = cache.computeIfAbsent((short) -6,
                        k -> bestOwnedScore(bot, ii, (short) -6, id -> true));
                if (slot == (short) -6) {
                    return crossSlotBar(overall, pants, top, false);
                }
                return crossSlotBar(overall, top, pants, ItemConstants.isOverall(itemId));
            }
            case -10, -11 -> {
                double oneH = cache.computeIfAbsent(KEY_BEST_1H_WEAPON,
                        k -> bestOwnedScore(bot, ii, (short) -11, id -> !ii.isTwoHanded(id)));
                double twoH = cache.computeIfAbsent(KEY_BEST_2H_WEAPON,
                        k -> bestOwnedScore(bot, ii, (short) -11, ii::isTwoHanded));
                double shield = cache.computeIfAbsent((short) -10,
                        k -> bestOwnedScore(bot, ii, (short) -10, id -> true));
                if (slot == (short) -10) {
                    return crossSlotBar(twoH, shield, oneH, false);
                }
                return crossSlotBar(twoH, oneH, shield, ii.isTwoHanded(itemId));
            }
            default -> {
                return cache.computeIfAbsent(slot, s -> bestOwnedScore(bot, ii, s, id -> true));
            }
        }
    }

    /** Pure bar math for a two-slot exclusivity family: the candidate must beat the best owned
     *  ensemble (the combined item vs the two pieces worn together) minus the partner piece it
     *  keeps; a combined candidate (overall, 2H weapon) displaces both, so it keeps nothing. */
    static double crossSlotBar(double combinedBest, double pieceBest, double partnerBest,
                               boolean candidateIsCombined) {
        double ensemble = Math.max(combinedBest, pieceBest + partnerBest);
        return candidateIsCombined ? ensemble : ensemble - partnerBest;
    }

    /**
     * The bar a new drop must beat: the best of the WORN item and every BAGGED equip for the
     * slot (restricted to {@code idFilter}), each at {@link BotScrollManager#potentialValue}
     * discounted by how long until the bot can wear it. Owning a better copy — even one
     * benched for a few levels — makes farming a weaker one pointless; a wear-now drop keeps
     * interim value against a bagged future item exactly as big as the discounted gap.
     */
    private static double bestOwnedScore(Character bot, ItemInformationProvider ii, short slot,
                                         IntPredicate idFilter) {
        double best = 0.0;
        Equip worn = BotScrollManager.wornInSlot(bot, ii, slot);
        if (worn != null && idFilter.test(worn.getItemId())) {
            best = BotScrollManager.potentialValue(bot, ii, worn)
                    * (slot == (short) -11 ? weaponSpeedFactor(worn.getItemId()) : 1.0);
        }
        for (Item it : bot.getInventory(InventoryType.EQUIP).list()) {
            if (!(it instanceof Equip e) || ii.isCash(e.getItemId())
                    || !idFilter.test(e.getItemId())) {
                continue;
            }
            Short s = BotScrollManager.primarySlot(ii, e.getItemId());
            if (s == null || s != slot) {
                continue;
            }
            int levelsToGo = BotScrollManager.levelsUntilWearable(bot, ii, e, GEAR_LEVEL_HORIZON);
            if (levelsToGo < 0) {
                continue;
            }
            best = Math.max(best,
                    levelDiscount(levelsToGo) * BotScrollManager.potentialValue(bot, ii, e)
                            * (slot == (short) -11 ? weaponSpeedFactor(e.getItemId()) : 1.0));
        }
        return best;
    }

    /**
     * DPS normalization for weapon scoring: scales an offense score by how fast the weapon
     * actually swings, using the same WZ animation x speed-tier cycle the equip optimizer
     * benchmarks with ({@link BotEquipManager#weaponCycleMs}). A slow 82 spear must out-roll
     * a fast 76 spear by the cycle ratio before it counts as an upgrade — otherwise the
     * advisor sends the party to farm a weapon autoEquip will only bench. Reference is the
     * advisor's own attack-cycle anchor, so a typical-speed weapon keeps its raw score;
     * 1.0 when WZ timing is unavailable (unit tests, odd items).
     */
    private static double weaponSpeedFactor(int itemId) {
        int cycleMs = BotEquipManager.weaponCycleMs(itemId);
        return cycleMs > 0 ? ATTACK_CYCLE_SECONDS * 1000.0 / cycleMs : 1.0;
    }

    /** One looted scroll's expected offense gain for THIS bot; test seam (the real lookup
     *  needs ItemInformationProvider, which can't load in unit tests). */
    @FunctionalInterface
    interface ScrollGainLookup {
        double gain(Character bot, int scrollId);
    }

    static ScrollGainLookup scrollGains = BotGrindAdvisor::scrollGain;

    /** Wires {@link #scrollExpectedGain} to the live catalog: skips meta scrolls (clean slate /
     *  modifier / white) and boom-risk ones (self-scrolling only uses those with fallback gear
     *  at use time, so their farm value is conditional — kept out conservatively), then checks
     *  the WORN gear for an applicable open-slot target. */
    private static double scrollGain(Character bot, int scrollId) {
        if (ItemConstants.isCleanSlate(scrollId) || ItemConstants.isModifierScroll(scrollId)
                || scrollId == ItemId.WHITE_SCROLL) {
            return 0.0;
        }
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        Map<String, Integer> st = ii.getEquipStats(scrollId);
        if (st == null || st.getOrDefault("cursed", 0) > 0) {
            return 0.0;
        }
        boolean hasTarget = false;
        for (Item it : bot.getInventory(InventoryType.EQUIPPED).list()) {
            if (it instanceof Equip eq && eq.getUpgradeSlots() >= 1
                    && BotScrollManager.applicable(ii, scrollId, eq.getItemId())) {
                hasTarget = true;
                break;
            }
        }
        return scrollExpectedGain(
                BotScrollManager.effectiveSuccessPct(st.getOrDefault("success", 0)) / 100.0,
                BotScrollManager.offenseValueFromStats(bot, st), hasTarget);
    }

    /**
     * Pure EV core of scroll valuation: {@code success x stat value} while the bot has an
     * applicable worn item with an open upgrade slot, nothing otherwise. No tiering anywhere —
     * a 60% scroll with a big payload out-values a safe 100% with a small one on expectation,
     * and att scrolls dominate stat scrolls through the offense SSOT's weights.
     */
    static double scrollExpectedGain(double successRate, double offenseGain, boolean hasOpenSlotTarget) {
        if (!hasOpenSlotTarget || successRate <= 0 || offenseGain <= 0) {
            return 0.0;
        }
        return successRate * offenseGain;
    }

    /** Offense scores of {@code n} fresh drop rolls of an item for this bot; test seam
     *  (ItemInformationProvider cannot load in unit tests). */
    @FunctionalInterface
    interface RollScoreSampler {
        double[] sample(Character bot, int itemId, int n);
    }

    static RollScoreSampler rollScores = BotGrindAdvisor::sampleRollScores;

    /** How a fresh copy of an item is rolled before scoring: a plain drop roll for farming, the Maker
     *  roll (reagents + stimulant upgrade/godly) for crafting. Lets all acquisition sources share the
     *  same scoring wrapper below so they rank on ONE scale (vs the same {@code bestOwnedScore} bar). */
    @FunctionalInterface
    interface EquipRoll {
        Equip roll(Equip base);
    }

    /** SSOT roll: the exact {@code randomizeStats(getEquipById(id))} call the map drop path uses
     *  (godly check included). */
    private static double[] sampleRollScores(Character bot, int itemId, int n) {
        return sampleEquipScores(bot, itemId, n,
                base -> ItemInformationProvider.getInstance().randomizeStats(base));
    }

    /**
     * Score {@code n} freshly-rolled copies of an item on the SSOT scale: offense PLUS scroll
     * headroom for the rolled upgrade slots (a fresh piece carries its full slots), x the weapon-speed
     * factor for weapons. The caller supplies the roll, so a drop and a Maker craft are valued
     * identically except for how the stats land — and both compare against the same
     * {@link #bestOwnedScore} baseline. Used by mob-drop farming and {@link BotMakerPlanner}.
     */
    static double[] sampleEquipScores(Character bot, int itemId, int n, EquipRoll roll) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        double evPerSlot = BotScrollManager.bestScrollEvPerSlot(bot, ii, itemId);
        Short slot = BotScrollManager.primarySlot(ii, itemId);
        double speed = slot != null && slot == (short) -11 ? weaponSpeedFactor(itemId) : 1.0;
        double[] out = new double[n];
        boolean prof = BotPerformanceMonitor.enabled();
        long rollNs = 0L;
        long scoreNs = 0L;
        for (int i = 0; i < n; i++) {
            long t0 = prof ? System.nanoTime() : 0L;
            Equip rolled = roll.roll((Equip) ii.getEquipById(itemId));
            long t1 = prof ? System.nanoTime() : 0L;
            out[i] = (BotScrollManager.offenseValue(bot, rolled)
                    + BotScrollManager.scrollHeadroom(rolled.getUpgradeSlots(), evPerSlot)) * speed;
            if (prof) {
                rollNs += t1 - t0;
                scoreNs += System.nanoTime() - t1;
            }
        }
        if (prof) {
            BotPerformanceMonitor.record("grind.roll", rollNs);
            BotPerformanceMonitor.record("grind.score", scoreNs);
        }
        return out;
    }

    /**
     * {@code E[max(0, roll - current)]} over the sampled roll scores: the value of farming
     * another copy. Monotonically shrinks as the current roll improves (a near-godly worn copy
     * makes almost every reroll worthless); an empty slot ({@code current = 0}) is worth the
     * full sample mean.
     */
    static double expectedImprovement(double[] sampleScores, double currentScore) {
        return expectedImprovement(sampleScores, 1.0, currentScore);
    }

    /** Like {@link #expectedImprovement(double[], double)} with each sample scaled first —
     *  the level discount of a not-yet-wearable drop applies to the ROLL, not the improvement,
     *  so a future drop competes symmetrically against future bagged items in the baseline. */
    static double expectedImprovement(double[] sampleScores, double sampleScale, double currentScore) {
        if (sampleScores == null || sampleScores.length == 0) {
            return 0.0;
        }
        double sum = 0.0;
        for (double s : sampleScores) {
            sum += Math.max(0.0, s * sampleScale - currentScore);
        }
        return sum / sampleScores.length;
    }

    private static double totalWornOffense(Character bot, ItemInformationProvider ii) {
        double total = 0.0;
        for (Item it : bot.getInventory(InventoryType.EQUIPPED).list()) {
            if (it instanceof Equip e && !ii.isCash(e.getItemId())) {
                total += BotScrollManager.offenseValue(bot, e);
            }
        }
        return total;
    }

    // ---- chat composition (ASCII only — see kb_bot_chat_charset_ascii_only) ----

    private static List<String> composeAdvice(Recommendation rec) {
        MobCandidate pick = rec.pick();
        List<String> lines = new ArrayList<>(2);
        String spot = pick.mapName().isEmpty() ? ("map " + pick.mapId()) : pick.mapName();
        if (rec.gearFocused()) {
            GearProspect want = rec.wantedGear();
            lines.add("i wanna farm " + want.itemName() + " from " + pick.mobName()
                    + " - about +" + Math.round(want.dpsGainFraction() * 100) + "% dps for me");
            lines.add("best spot: " + spot + " (" + pick.spawnPoints() + " spawns), ~"
                    + GameConstants.numberWithCommas((int) Math.round(rec.expPerHour())) + " exp/hr on the side");
        } else {
            lines.add("best exp for me: " + pick.mobName() + " at " + spot
                    + " - ~" + GameConstants.numberWithCommas((int) Math.round(rec.expPerHour()))
                    + " exp/hr (" + pick.spawnPoints() + " spawns)");
            if (rec.wantedGear() != null) {
                lines.add("bonus: they drop " + rec.wantedGear().itemName() + " (+"
                        + Math.round(rec.wantedGear().dpsGainFraction() * 100) + "% dps) if we get lucky");
            }
        }
        return lines;
    }

    /** "grind debug": dump the top candidates with both lenses' numbers to a report file. */
    static void exportGrindDecision(BotEntry entry, Character bot) {
        DECIDE_POOL.execute(() -> exportGrindDecisionBlocking(entry, bot));
    }

    private static void exportGrindDecisionBlocking(BotEntry entry, Character bot) {
        List<MobCandidate> candidates;
        try {
            candidates = buildCandidates(entry, bot, mapId -> true);
        } catch (RuntimeException e) {
            log.warn("Grind debug failed for {}", bot.getName(), e);
            BotManager.getInstance().botReply(entry, "grind debug blew up, check the log");
            return;
        }
        candidates.sort((a, b) -> Double.compare(
                b.exp() * BotGrindPlanner.killsPerHour(b), a.exp() * BotGrindPlanner.killsPerHour(a)));
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("grind candidates for %s (lv %d), %d total%n%n",
                bot.getName(), bot.getLevel(), candidates.size()));
        int shown = 0;
        for (MobCandidate c : candidates) {
            if (shown++ >= 40) {
                break;
            }
            double kph = BotGrindPlanner.killsPerHour(c);
            sb.append(String.format("%-24s lv%-3d  %-28s map=%d spawns=%-3d kill=%5.1fs  %,9.0f exp/hr%n",
                    c.mobName(), c.mobLevel(), c.mapName(), c.mapId(), c.spawnPoints(),
                    c.killSeconds(), c.exp() * kph));
            for (GearProspect g : c.gearDrops()) {
                sb.append(String.format("    want: %-26s p=%.4f/kill  +%.1f score (+%d%% dps)  desirability=%.3f%n",
                        g.itemName(), g.chancePerKill(), g.scoreGain(),
                        Math.round(g.dpsGainFraction() * 100), BotGrindPlanner.desirability(g, kph)));
            }
        }
        Recommendation rec = BotGrindPlanner.planBest(candidates, ThreadLocalRandom.current());
        if (rec != null) {
            sb.append(String.format("%npick: %s @ %s  needGear=%.2f gearFocused=%s%n",
                    rec.pick().mobName(), rec.pick().mapName(), rec.needGear(), rec.gearFocused()));
        }
        String path = writeReport(bot, sb.toString());
        BotManager.getInstance().botReply(entry,
                path != null ? "wrote it to " + path : "couldn't write the grind report");
    }

    /** "grind profile": measure the REAL party grind-decision under live load. Runs the same
     *  read-only pipeline {@link BotAutopilotManager#partyDecider} uses (partyInputs ->
     *  planPartyBest) TWICE and times it with nanoTime, so run1 (likely cold) vs run2 (warm)
     *  separates one-time cache warm-up from steady-state/contention cost. Measures only - no
     *  global BotPerformanceMonitor toggle, no plan applied. */
    static void exportGrindProfile(BotEntry entry, Character bot) {
        DECIDE_POOL.execute(() -> exportGrindProfileBlocking(entry, bot));
    }

    private static void exportGrindProfileBlocking(BotEntry entry, Character bot) {
        // Same member set party-autopilot decides over; empty (not autopiloting) -> solo.
        List<BotEntry> members = BotAutopilotManager.partyMembers.members(entry);
        if (members.isEmpty()) {
            members = List.of(entry);
        }
        int liveBots = BotManager.getInstance().activeBotCount();
        long run1Ms, run2Ms, planMs;
        long[] memberMs = new long[members.size()];
        int[] memberCands = new int[members.size()];
        try {
            // Run 1: end-to-end (partyInputs is the heavy part; planPartyBest is pure scoring).
            long t0 = System.nanoTime();
            BotAutopilotManager.PartyInputs in1 = BotAutopilotManager.partyInputs(members);
            BotGrindPlanner.planPartyBest(in1.perMember(), in1.weights(), ThreadLocalRandom.current());
            run1Ms = (System.nanoTime() - t0) / 1_000_000;

            // Run 2: warm, with a planPartyBest split-out and a per-member candidatesFor breakdown.
            t0 = System.nanoTime();
            BotAutopilotManager.PartyInputs in2 = BotAutopilotManager.partyInputs(members);
            long tMid = System.nanoTime();
            BotGrindPlanner.planPartyBest(in2.perMember(), in2.weights(), ThreadLocalRandom.current());
            long t1 = System.nanoTime();
            run2Ms = (t1 - t0) / 1_000_000;
            planMs = (t1 - tMid) / 1_000_000;
            for (int i = 0; i < members.size(); i++) {
                BotEntry m = members.get(i);
                long ts = System.nanoTime();
                List<MobCandidate> cands = candidatesFor(m, m.bot, in2.allowed()::contains);
                memberMs[i] = (System.nanoTime() - ts) / 1_000_000;
                memberCands[i] = cands.size();
            }
        } catch (RuntimeException e) {
            log.warn("Grind profile failed for {}", bot.getName(), e);
            BotManager.getInstance().botReply(entry, "grind profile blew up, check the log");
            return;
        }

        String diag;
        if (run1Ms > run2Ms * 3 && run1Ms - run2Ms > 50) {
            diag = "COLD CACHE TAX (one-time warm-up dominates)";
        } else if (run1Ms > 100 && run2Ms > 100) {
            diag = "STEADY-STATE/CONTENTION (caches hot, cost is per-decision or CPU contention)";
        } else {
            diag = "warm and cheap";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("grind profile for %s (lv %d) @ %tF %<tT%n%n",
                bot.getName(), bot.getLevel(), System.currentTimeMillis()));
        sb.append(String.format("live bots: %d   party profiled: %d%n", liveBots, members.size()));
        sb.append(String.format("run1 (cold?): %d ms   run2 (warm): %d ms%n", run1Ms, run2Ms));
        sb.append(String.format("run2 planPartyBest: %d ms%n%n", planMs));
        sb.append("run2 per-member candidatesFor:\n");
        for (int i = 0; i < members.size(); i++) {
            sb.append(String.format("  %-16s %4d ms   %d candidates%n",
                    members.get(i).bot.getName(), memberMs[i], memberCands[i]));
        }
        sb.append(String.format("%ndiagnosis: %s%n", diag));

        String path = writeReport(bot, "grind-profile-", sb.toString());
        BotManager.getInstance().botReply(entry, String.format(
                "grind profile: run1=%dms run2=%dms, %d bots live -> %s",
                run1Ms, run2Ms, liveBots, path != null ? "wrote " + path : "report write failed"));
    }

    private static String writeReport(Character bot, String report) {
        return writeReport(bot, "grind-debug-", report);
    }

    /** Shared bot-debug report sink: {@code logs/bot-grind/<prefix><botName>.txt}. The prefix
     *  ("grind-debug-" / "ap-debug-") names the report; null on I/O failure. */
    static String writeReport(Character bot, String prefix, String report) {
        try {
            String safe = bot.getName() == null ? "bot" : bot.getName().replaceAll("[^A-Za-z0-9_]", "");
            java.nio.file.Path dir = java.nio.file.Path.of("logs", "bot-grind");
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Path p = dir.resolve(prefix + safe + ".txt").toAbsolutePath();
            java.nio.file.Files.writeString(p, report);
            return p.toString();
        } catch (java.io.IOException e) {
            return null;
        }
    }

    private static String mobName(MonsterInformationProvider mi, int mobId) {
        String name = mi.getMobNameFromId(mobId);
        return name != null && !name.isEmpty() ? name : ("mob " + mobId);
    }

    /** Map names are static WZ data, but loadPlaceName walks String.wz on every call (behind a
     *  synchronized provider) and a pass asks for thousands — cache forever. */
    private static final java.util.concurrent.ConcurrentHashMap<Integer, String> mapNameCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static String mapName(int mapId) {
        return mapNameCache.computeIfAbsent(mapId, id -> {
            try {
                String name = MapFactory.loadPlaceName(id);
                return name != null ? name : "";
            } catch (RuntimeException e) {
                return "";
            }
        });
    }

    private static String itemName(ItemInformationProvider ii, int itemId) {
        String name = ii.getName(itemId);
        return name != null ? name : ("item " + itemId);
    }

    /** Equip + equip-scroll drops per mob from {@code drop_data} (populate-once, same pattern
     *  as shopPrices). */
    private static Map<Integer, List<int[]>> gearDropsByMob() {
        Map<Integer, List<int[]>> cached = gearDropsByMob;
        if (cached != null) {
            return cached;
        }
        Map<Integer, List<int[]>> m = new HashMap<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT dropperid, itemid, chance FROM drop_data"
                             + " WHERE chance > 0 AND (itemid BETWEEN 1000000 AND 1999999"
                             + " OR itemid BETWEEN 2040000 AND 2049999)");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                m.computeIfAbsent(rs.getInt("dropperid"), k -> new ArrayList<>())
                        .add(new int[]{rs.getInt("itemid"), rs.getInt("chance")});
            }
        } catch (SQLException e) {
            log.warn("Couldn't load gear drops for grind advisor", e);
        }
        gearDropsByMob = m;
        return m;
    }
}
