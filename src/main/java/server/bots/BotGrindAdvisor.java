package server.bots;

import client.Character;
import client.inventory.Equip;
import client.inventory.InventoryType;
import client.inventory.Item;
import config.YamlConfig;
import constants.game.GameConstants;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * "Where should we grind?" — builds real candidates for {@link BotGrindPlanner} from the bot's own
 * state and world data, then explains the pick in chat. Decision + reasoning only: the bot does NOT
 * travel anywhere (bots are owner-tethered companions; travel is a later slice).
 *
 * <p>Candidate = (mob, map): every mob in the {@link BotSpawnIndex} the bot can actually damage,
 * at its densest spawn sites. Kill time uses the combat SSOT ({@code estimateBestSkillHitDamage},
 * physical fallback) — the same model as the scroll farming-cost anchor. Gear prospects = equip
 * drops ({@code drop_data}) that this bot can wear and that beat what it currently has in that
 * slot, scored as the <em>expected</em> roll: catalog stats (vanilla roll is symmetric, so its
 * mean is the catalog value) plus the godly-roll mixture's expected bonus on stats the item
 * already has. The planner balances exp/h vs gear progression dynamically — see its javadoc.
 *
 * <p>Skipped on purpose (documented, not silent): towns, instanced/event fields (mapId &ge;
 * 900000000), sites with fewer than {@link #MIN_SPAWN_POINTS} spawn points, bosses, friendlies,
 * 0-exp props, and {@code drop_data_global} (it adds the same items to every mob, washing out
 * map differentiation).
 */
final class BotGrindAdvisor {

    private static final Logger log = LoggerFactory.getLogger(BotGrindAdvisor.class);

    /** Same producer anchors as the scroll farming-cost model. */
    private static final double ATTACK_CYCLE_SECONDS = 0.72;
    private static final double DROP_CHANCE_DENOMINATOR = 1_000_000.0;
    private static final int MIN_SPAWN_POINTS = 3;
    private static final int MAX_SITES_PER_MOB = 2;
    private static final int INSTANCED_MAPID_FLOOR = 900000000;
    /** Ignore "upgrades" below this offense-score gain — rounding noise, not progression. */
    private static final double MIN_GEAR_GAIN_SCORE = 0.5;

    /** Lazily-loaded equip drops per mob: mobId → list of {itemId, chance}. */
    private static volatile Map<Integer, List<int[]>> equipDropsByMob;

    private BotGrindAdvisor() {}

    /** Owner asked where to grind: decide, then explain the what/why in chat. */
    static void requestGrindAdvice(BotEntry entry, Character bot) {
        if (entry == null || bot == null) {
            return;
        }
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
    }

    /** Full decision pass over the world. Heavy-ish on first call (WZ mob loads); fine async. */
    static Recommendation recommend(BotEntry entry, Character bot) {
        return recommend(entry, bot, mapId -> true);
    }

    /** Decision pass restricted to allowed maps (autopilot: only maps the bot can walk to). */
    static Recommendation recommend(BotEntry entry, Character bot, java.util.function.IntPredicate mapAllowed) {
        List<MobCandidate> candidates = buildCandidates(entry, bot);
        candidates.removeIf(c -> !mapAllowed.test(c.mapId()));
        return BotGrindPlanner.planBest(candidates, ThreadLocalRandom.current());
    }

    /** Candidate pool for external planners (party autopilot). Same pool recommend() uses. */
    static List<MobCandidate> candidatesFor(BotEntry entry, Character bot) {
        return buildCandidates(entry, bot);
    }

    private static List<MobCandidate> buildCandidates(BotEntry entry, Character bot) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        BotSpawnIndex.Index index = BotSpawnIndex.get();
        MonsterInformationProvider mi = MonsterInformationProvider.getInstance();

        double totalWornOffense = totalWornOffense(bot, ii);
        Map<Short, Double> wornScoreBySlot = new HashMap<>();

        List<MobCandidate> candidates = new ArrayList<>();
        for (Map.Entry<Integer, List<BotSpawnIndex.SpawnSite>> e : index.byMob().entrySet()) {
            int mobId = e.getKey();
            Monster mob;
            try {
                mob = LifeFactory.getMonster(mobId);
            } catch (RuntimeException ex) {
                continue;
            }
            if (mob == null || mob.getStats() == null) {
                continue;
            }
            var stats = mob.getStats();
            if (stats.isBoss() || stats.isFriendly() || stats.getExp() <= 0) {
                continue;
            }

            double killSeconds = killSeconds(entry, bot, mob);
            if (killSeconds <= 0) {
                continue; // can't meaningfully damage it
            }

            List<GearProspect> gear = gearProspects(bot, ii, mobId, wornScoreBySlot, totalWornOffense);
            int exp = stats.getExp() * bot.getExpRate();
            addSiteCandidates(candidates, index, mi, e.getValue(), mobId, stats.getLevel(), exp,
                    killSeconds, gear, mapId -> true);
        }
        return candidates;
    }

    /** Shared site filter: top-N densest, populated, non-town, non-instanced, allowed maps. */
    private static void addSiteCandidates(List<MobCandidate> out, BotSpawnIndex.Index index,
                                          MonsterInformationProvider mi,
                                          List<BotSpawnIndex.SpawnSite> sites,
                                          int mobId, int mobLevel, int exp, double killSeconds,
                                          List<GearProspect> gear,
                                          java.util.function.IntPredicate mapAllowed) {
        int sitesUsed = 0;
        for (BotSpawnIndex.SpawnSite site : sites) {
            if (sitesUsed >= MAX_SITES_PER_MOB) {
                break;
            }
            if (site.spawnPoints() < MIN_SPAWN_POINTS || site.mapId() >= INSTANCED_MAPID_FLOOR
                    || !mapAllowed.test(site.mapId())) {
                continue;
            }
            BotSpawnIndex.MapSpawns map = index.byMap().get(site.mapId());
            if (map == null || map.town()) {
                continue;
            }
            out.add(new MobCandidate(
                    mobId, mobName(mi, mobId), mobLevel, exp, killSeconds,
                    site.mapId(), mapName(site.mapId()), site.spawnPoints(), gear));
            sitesUsed++;
        }
    }

    /**
     * "farm &lt;item&gt;": candidates are every allowed site of every mob that drops the item,
     * handed to {@link BotGrindPlanner#planFarmBest} which scores purely by expected items/hour.
     * Null when nothing the bot can reach (and damage) drops it.
     */
    static Recommendation recommendFarmItem(BotEntry entry, Character bot, int itemId,
                                            java.util.function.IntPredicate mapAllowed) {
        Map<Integer, Integer> droppers = droppersForItem.droppers(itemId);
        if (droppers.isEmpty()) {
            return null;
        }
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        MonsterInformationProvider mi = MonsterInformationProvider.getInstance();
        BotSpawnIndex.Index index = BotSpawnIndex.get();
        String name = itemName(ii, itemId);

        List<MobCandidate> candidates = new ArrayList<>();
        for (Map.Entry<Integer, Integer> dropper : droppers.entrySet()) {
            int mobId = dropper.getKey();
            Monster mob;
            try {
                mob = LifeFactory.getMonster(mobId);
            } catch (RuntimeException ex) {
                continue;
            }
            if (mob == null || mob.getStats() == null) {
                continue;
            }
            var stats = mob.getStats();
            if (stats.isBoss() || stats.isFriendly()) {
                continue;
            }
            double killSeconds = killSeconds(entry, bot, mob);
            if (killSeconds <= 0) {
                continue;
            }
            double chancePerKill = Math.min(1.0,
                    dropper.getValue() * bot.getDropRate() / DROP_CHANCE_DENOMINATOR);
            List<GearProspect> objective = List.of(new GearProspect(itemId, name, chancePerKill, 0, 0));
            int exp = stats.getExp() * bot.getExpRate();
            addSiteCandidates(candidates, index, mi, BotSpawnIndex.spawnSites(mobId),
                    mobId, stats.getLevel(), exp, killSeconds, objective, mapAllowed);
        }
        return BotGrindPlanner.planFarmBest(candidates, ThreadLocalRandom.current());
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

    /** Wearable equip drops of this mob that beat the bot's current item in that slot. */
    private static List<GearProspect> gearProspects(Character bot, ItemInformationProvider ii, int mobId,
                                                    Map<Short, Double> wornScoreBySlot, double totalWornOffense) {
        List<int[]> drops = equipDropsByMob().get(mobId);
        if (drops == null) {
            return List.of();
        }
        List<GearProspect> out = new ArrayList<>(2);
        for (int[] drop : drops) {
            int itemId = drop[0];
            Map<String, Integer> st = ii.getEquipStats(itemId);
            if (st == null) {
                continue;
            }
            Short slot = BotScrollManager.primarySlot(ii, itemId);
            if (slot == null) {
                continue;
            }
            Item catalog;
            try {
                catalog = ii.getEquipById(itemId);
            } catch (RuntimeException ex) {
                continue;
            }
            if (!(catalog instanceof Equip eq) || !BotScrollManager.wearable(bot, ii, eq)) {
                continue;
            }
            double expectedScore = expectedDropScore(bot, ii, itemId, st);
            double wornScore = wornScoreBySlot.computeIfAbsent(slot, s -> {
                Equip worn = BotScrollManager.wornInSlot(bot, ii, s);
                return worn != null ? BotScrollManager.offenseValue(bot, worn) : 0.0;
            });
            double gain = expectedScore - wornScore;
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

    /**
     * Expected offense score of a fresh drop: the vanilla roll is symmetric around catalog stats
     * (mean = catalog), and with probability GODLY_STATS_DROP_CHANCE each already-present stat gains
     * Uniform{0..maxBonus} (mean maxBonus/2). The presence-weight trick reuses the job-weighted
     * scoring SSOT: offenseValueFromStats over a 0/1 presence map = the summed weights of present
     * offense stats.
     */
    private static double expectedDropScore(Character bot, ItemInformationProvider ii,
                                            int itemId, Map<String, Integer> st) {
        double base = BotScrollManager.offenseValueFromStats(bot, st);
        if (!YamlConfig.config.server.GODLY_STATS_ENABLED) {
            return base;
        }
        Map<String, Integer> presence = new HashMap<>();
        for (Map.Entry<String, Integer> e : st.entrySet()) {
            if (e.getValue() != null && e.getValue() > 0) {
                presence.put(e.getKey(), 1);
            }
        }
        double presentWeights = BotScrollManager.offenseValueFromStats(bot, presence);
        int reqLevel = st.getOrDefault("reqLevel", 0);
        int maxBonus = Math.max(Math.round((float) (reqLevel * YamlConfig.config.server.GODLY_STATS_BONUS_SCALING)),
                YamlConfig.config.server.GODLY_STATS_MIN_BONUS);
        double pGodly = YamlConfig.config.server.GODLY_STATS_DROP_CHANCE / 100.0;
        return base + pGodly * (maxBonus / 2.0) * presentWeights;
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
        List<MobCandidate> candidates;
        try {
            candidates = buildCandidates(entry, bot);
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

    private static String mapName(int mapId) {
        try {
            String name = MapFactory.loadPlaceName(mapId);
            return name != null ? name : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String itemName(ItemInformationProvider ii, int itemId) {
        String name = ii.getName(itemId);
        return name != null ? name : ("item " + itemId);
    }

    /** Equip drops per mob from {@code drop_data} (populate-once, same pattern as shopPrices). */
    private static Map<Integer, List<int[]>> equipDropsByMob() {
        Map<Integer, List<int[]>> cached = equipDropsByMob;
        if (cached != null) {
            return cached;
        }
        Map<Integer, List<int[]>> m = new HashMap<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT dropperid, itemid, chance FROM drop_data"
                             + " WHERE chance > 0 AND itemid BETWEEN 1000000 AND 1999999");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                m.computeIfAbsent(rs.getInt("dropperid"), k -> new ArrayList<>())
                        .add(new int[]{rs.getInt("itemid"), rs.getInt("chance")});
            }
        } catch (SQLException e) {
            log.warn("Couldn't load equip drops for grind advisor", e);
        }
        equipDropsByMob = m;
        return m;
    }
}
