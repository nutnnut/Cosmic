package server.bots;

import client.Character;
import client.inventory.Equip;
import client.inventory.Item;
import client.inventory.InventoryType;
import constants.inventory.ItemConstants;
import server.ItemInformationProvider;
import client.processor.action.MakerProcessor;
import server.bots.BotGrindAdvisor.RollScoreSampler;
import config.YamlConfig;
import tools.DatabaseConnection;
import tools.Pair;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only planner: which equips the bot could Maker-craft right now that would be a gear UPGRADE,
 * ranked by expected offense gain over what it already owns. Reuses the SSOT expected-gain math
 * ({@link BotGrindAdvisor#expectedAcquireGain}) that mob-drop farming uses — the only difference is the
 * roll sampler: instead of a plain drop roll, it samples the actual Maker roll (chosen stimulant +
 * stat-crystal reagents, with the godly upgrade roll), so a crafted piece is valued on the exact same
 * scale as a farmed drop. Dynamic, not hardcoded: reagents are chosen by their offense value to this
 * job, and crystals that ADD a stat make that stat godly-eligible (see {@link #reagentStatMap}).
 *
 * <p>This class does NOT craft anything — it only ranks. Execution (spending mesos + consuming
 * materials) is a separate, owner-gated step in {@link BotMakerManager}.
 */
final class BotMakerPlanner {
    private static final int ROLL_SAMPLES = 32;        // matches BotGrindAdvisor's drop sampling
    private static final double MIN_GAIN = 1.0;        // ignore negligible "upgrades"
    private static final int REAGENT_ITEM_PREFIX = 425; // itemId / 10000 == 425 -> maker stat crystal

    private BotMakerPlanner() {
    }

    record CraftPlan(int itemId, String name, double expectedGain, int mesoCost,
                     int stimulantId, Map<Integer, Short> reagentIds, String reagentDesc) {
    }

    private record Recipe(int itemId, int reqMakerLevel, int reqMeso, List<int[]> reqItems) {
    }

    private static volatile Map<Integer, Recipe> equipRecipes;

    /** Ranked craftable equip upgrades for this bot (highest expected gain first); empty if it has no
     *  Maker skill. Read-only and command-triggered, so the per-recipe sampling cost is fine. */
    static List<CraftPlan> rankUpgrades(Character bot) {
        if (bot == null || MakerProcessor.getMakerSkillLevel(bot) < 1) {
            return List.of();
        }
        int makerLvl = MakerProcessor.getMakerSkillLevel(bot);
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        Map<Short, Double> barCache = new HashMap<>();
        Map<Integer, Short> ownedReagents = ownedReagents(bot);
        List<CraftPlan> out = new ArrayList<>();

        for (Recipe r : equipRecipes().values()) {
            if (r.reqMakerLevel() > makerLvl || !hasIngredients(bot, ii, r)) {
                continue;
            }
            int stimulantId = chooseStimulant(ii, bot, r.itemId());
            Map<Integer, Short> reagents = chooseReagents(ii, bot, r.itemId(), ownedReagents);
            Map<String, Integer> reagentStats = reagentStatMap(ii, reagents);
            boolean useStim = stimulantId != -1;
            RollScoreSampler sampler =
                    (b, id, n) -> sampleMakerRoll(ii, b, id, n, reagentStats, useStim);

            double gain;
            try {
                gain = BotGrindAdvisor.expectedAcquireGain(bot, ii, r.itemId(), sampler, ROLL_SAMPLES, barCache);
            } catch (RuntimeException ex) {
                continue;
            }
            if (gain < MIN_GAIN) {
                continue;
            }
            out.add(new CraftPlan(r.itemId(), ii.getName(r.itemId()), gain, r.reqMeso(),
                    stimulantId, reagents, describeReagents(ii, reagents, stimulantId)));
        }
        out.sort(Comparator.comparingDouble(CraftPlan::expectedGain).reversed());
        return out;
    }

    /** Sample the Maker roll N times via the SSOT scorer ({@link BotGrindAdvisor#sampleEquipScores}),
     *  so a craft is valued exactly like a drop (offense + scroll headroom x weapon speed) and compares
     *  against the same owned baseline. The roll mirrors {@link MakerProcessor}'s addBoostedMakerItem:
     *  reagent stats applied first (deterministic, and they make those stats godly-eligible), then the
     *  stimulant upgrade roll ({@code randomizeUpgradeStats} = +0..2/+0..5 then a godly chance). */
    private static double[] sampleMakerRoll(ItemInformationProvider ii, Character bot, int itemId, int n,
                                            Map<String, Integer> reagentStats, boolean useStim) {
        return BotGrindAdvisor.sampleEquipScores(bot, itemId, n, base -> {
            if (!reagentStats.isEmpty()) {
                ItemInformationProvider.improveEquipStats(base, reagentStats);
            }
            return useStim ? ii.randomizeUpgradeStats(base) : base;
        });
    }

    private static Map<Integer, Short> ownedReagents(Character bot) {
        Map<Integer, Short> owned = new HashMap<>();
        var etc = bot.getInventory(InventoryType.ETC);
        if (etc == null) {
            return owned;
        }
        for (Item item : etc.list()) {
            if (ItemConstants.isMakerReagent(item.getItemId())) {
                owned.merge(item.getItemId(), item.getQuantity(), (a, b) -> (short) (a + b));
            }
        }
        return owned;
    }

    // Maker gem "type" = reagentId / 100. Types below this are WATK/MATK gems (diamonds, black
    // crystals); the server's removeOddMakerReagents REJECTS the whole craft if one is used on a
    // non-weapon ("only weapons should gain w.att/m.att from these"), so the planner must never
    // propose them on armour/accessories. The client enforces the same — diamonds go on weapons only.
    private static final int ATTACK_GEM_TYPE_CEILING = 42502;

    /** Pick the recipe's reagent crystals to maximize offense for this job, mirroring the server's
     *  {@code removeOddMakerReagents} constraints so the plan is actually craftable: WATK/MATK gems
     *  (diamonds) only on weapons, at most one gem per type, and exactly one of each (the Maker skill
     *  consumes one). Dynamic by offense value, not a hardcoded list. */
    private static Map<Integer, Short> chooseReagents(ItemInformationProvider ii, Character bot, int itemId,
                                                      Map<Integer, Short> ownedReagents) {
        int slots = reagentSlots(ii, itemId);
        Map<Integer, Short> chosen = new LinkedHashMap<>();
        if (slots <= 0 || ownedReagents.isEmpty()) {
            return chosen;
        }
        // Mirror MakerProcessor.removeOddMakerReagents EXACTLY: it OR's isWeapon with the permissive
        // config, so under USE_MAKER_PERMISSIVE_ATKUP the server accepts att/matt gems on borderline
        // items isWeapon() rejects. Without this the planner would skip the gem and craft sub-optimally.
        boolean isWeapon = ItemConstants.isWeapon(itemId) || YamlConfig.config.server.USE_MAKER_PERMISSIVE_ATKUP;
        List<Map.Entry<Integer, Short>> ranked = new ArrayList<>(ownedReagents.entrySet());
        ranked.sort(Comparator.comparingDouble((Map.Entry<Integer, Short> e) ->
                reagentOffenseValue(ii, bot, e.getKey())).reversed());
        java.util.Set<Integer> usedTypes = new java.util.HashSet<>();
        for (Map.Entry<Integer, Short> e : ranked) {
            if (chosen.size() >= slots) {
                break;
            }
            int reagentId = e.getKey();
            if (reagentId / 100 < ATTACK_GEM_TYPE_CEILING && !isWeapon) {
                continue; // att/matt gem on a non-weapon -> server rejects the whole craft
            }
            if (!usedTypes.add(reagentId / 100)) {
                continue; // one gem per type (server keeps only the best of repeated types)
            }
            if (reagentOffenseValue(ii, bot, reagentId) <= 0.0) {
                continue; // no offense benefit for this job -> don't waste a slot/crystal
            }
            chosen.put(reagentId, (short) 1); // the Maker skill consumes exactly one of each gem
        }
        return chosen;
    }

    private static double reagentOffenseValue(ItemInformationProvider ii, Character bot, int reagentId) {
        Map<String, Integer> stat = singleReagentStat(ii, reagentId);
        return stat.isEmpty() ? 0.0 : BotScrollManager.offenseValueFromStats(bot, stat);
    }

    /** One reagent's stat contribution as an equip-stat map (e.g. {@code {PAD:1}}); empty for random
     *  /unknown reagents. Same key transform Maker uses (strip "inc", MaxHP->MHP, MaxMP->MMP). */
    private static Map<String, Integer> singleReagentStat(ItemInformationProvider ii, int reagentId) {
        Pair<String, Integer> buff = ii.getMakerReagentStatUpgrade(reagentId);
        Map<String, Integer> out = new HashMap<>();
        if (buff == null) {
            return out;
        }
        String key = buff.getLeft();
        if (key == null || key.length() < 4 || key.regionMatches(0, "rand", 0, 4)) {
            return out; // randStat/randOption: not a deterministic stat we can value cheaply
        }
        String stat = key.substring(3);
        if (stat.equals("ReqLevel")) {
            return out;
        }
        if (stat.equals("MaxHP")) {
            stat = "MHP";
        } else if (stat.equals("MaxMP")) {
            stat = "MMP";
        }
        out.put(stat, buff.getRight());
        return out;
    }

    private static Map<String, Integer> reagentStatMap(ItemInformationProvider ii, Map<Integer, Short> reagents) {
        Map<String, Integer> stats = new HashMap<>();
        for (Map.Entry<Integer, Short> r : reagents.entrySet()) {
            for (Map.Entry<String, Integer> s : singleReagentStat(ii, r.getKey()).entrySet()) {
                stats.merge(s.getKey(), s.getValue() * r.getValue(), Integer::sum);
            }
        }
        return stats;
    }

    private static int chooseStimulant(ItemInformationProvider ii, Character bot, int itemId) {
        int stim = ii.getMakerStimulant(itemId);
        if (stim == -1) {
            return -1;
        }
        var etc = bot.getInventory(InventoryType.ETC);
        return etc != null && etc.countById(stim) > 0 ? stim : -1;
    }

    /** Maker reagent slots by equip level (mirror of the private rule in MakerProcessor). */
    private static int reagentSlots(ItemInformationProvider ii, int itemId) {
        int lvl = ii.getEquipLevelReq(itemId);
        if (lvl < 78) {
            return 1;
        }
        return lvl < 108 ? 2 : 3;
    }

    private static boolean hasIngredients(Character bot, ItemInformationProvider ii, Recipe r) {
        for (int[] req : r.reqItems()) {
            var inv = bot.getInventory(ItemConstants.getInventoryType(req[0]));
            if (inv == null || inv.countById(req[0]) < req[1]) {
                return false;
            }
        }
        return true;
    }

    private static String describeReagents(ItemInformationProvider ii, Map<Integer, Short> reagents, int stim) {
        StringBuilder sb = new StringBuilder(stim != -1 ? "stim" : "no-stim");
        for (Map.Entry<Integer, Short> r : reagents.entrySet()) {
            sb.append(", ").append(r.getValue()).append("x ").append(ii.getName(r.getKey()));
        }
        return sb.toString();
    }

    private static Map<Integer, Recipe> equipRecipes() {
        Map<Integer, Recipe> cached = equipRecipes;
        if (cached != null) {
            return cached;
        }
        Map<Integer, Recipe> m = new HashMap<>();
        try (Connection con = DatabaseConnection.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT itemid, req_maker_level, req_meso FROM makercreatedata "
                            + "WHERE itemid BETWEEN 1000000 AND 1999999");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    m.put(rs.getInt("itemid"), new Recipe(rs.getInt("itemid"),
                            rs.getInt("req_maker_level"), rs.getInt("req_meso"), new ArrayList<>()));
                }
            }
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT itemid, req_item, count FROM makerrecipedata "
                            + "WHERE itemid BETWEEN 1000000 AND 1999999");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Recipe r = m.get(rs.getInt("itemid"));
                    if (r != null) {
                        r.reqItems().add(new int[]{rs.getInt("req_item"), rs.getInt("count")});
                    }
                }
            }
        } catch (SQLException e) {
            // leave whatever loaded; an empty map just means "nothing to craft"
        }
        equipRecipes = m;
        return m;
    }
}
