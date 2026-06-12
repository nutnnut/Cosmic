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
        List<MobCandidate> candidates = buildCandidates(entry, bot, mapAllowed);
        return BotGrindPlanner.planBest(candidates, mapScoreWeight, ThreadLocalRandom.current());
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
            candidates.add(blendCandidate(map.mapId(), mapName(map.mapId()), map.areaPx(),
                    pointsByMob));
        }
        return candidates;
    }

    /** A mob's bot-specific grind numbers, computed once per pass. Exp is rate-multiplied;
     *  prospect chances are per kill OF THIS MOB (the map blend dilutes them by spawn share). */
    record MobProfile(int mobId, String mobName, int level, int exp, double killSeconds,
                      List<GearProspect> prospects) {}

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
        double killSeconds = killSeconds(entry, bot, mob);
        if (killSeconds <= 0) {
            return null;
        }
        List<GearProspect> gear = withProspects
                ? gearProspects(bot, ii, mobId, wornScoreBySlot, rollScoreCache, scrollGainCache,
                        totalWornOffense)
                : List.of();
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
        return BotGrindPlanner.planFarmBest(candidates, mapScoreWeight, ThreadLocalRandom.current());
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
        return Math.max(ATTACK_CYCLE_SECONDS, Math.max(1, mob.getMaxHp()) / dps);
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
     *  OWNS for the slot (worn or bagged), both sides discounted by how long until wearable. */
    private static double equipGain(Character bot, ItemInformationProvider ii, int itemId,
                                    Map<Short, Double> wornScoreBySlot,
                                    Map<Integer, double[]> rollScoreCache) {
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
        double ownedScore = gearBar(bot, ii, itemId, slot, wornScoreBySlot);
        double[] samples = rollScoreCache.computeIfAbsent(itemId,
                id -> rollScores.sample(bot, id, ROLL_SAMPLES));
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

    /** SSOT roll: the exact {@code randomizeStats(getEquipById(id))} call the map drop path
     *  uses (godly check included), scored with the same offense SSOT as worn gear PLUS scroll
     *  headroom — a fresh drop carries its full upgrade slots, so it can out-value a stronger
     *  but maxed-out worn item when its type scrolls well (gloves price att scrolls; most
     *  pieces only stat scrolls). */
    private static double[] sampleRollScores(Character bot, int itemId, int n) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        double evPerSlot = BotScrollManager.bestScrollEvPerSlot(bot, ii, itemId);
        Short slot = BotScrollManager.primarySlot(ii, itemId);
        double speed = slot != null && slot == (short) -11 ? weaponSpeedFactor(itemId) : 1.0;
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            Equip rolled = ii.randomizeStats((Equip) ii.getEquipById(itemId));
            out[i] = (BotScrollManager.offenseValue(bot, rolled)
                    + BotScrollManager.scrollHeadroom(rolled.getUpgradeSlots(), evPerSlot)) * speed;
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

    private static String writeReport(Character bot, String report) {
        try {
            String safe = bot.getName() == null ? "bot" : bot.getName().replaceAll("[^A-Za-z0-9_]", "");
            java.nio.file.Path dir = java.nio.file.Path.of("logs", "bot-grind");
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Path p = dir.resolve("grind-debug-" + safe + ".txt").toAbsolutePath();
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
