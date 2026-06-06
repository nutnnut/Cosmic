package service;

import client.Character;
import constants.id.ItemId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static java.util.concurrent.TimeUnit.HOURS;

/**
 * Level-milestone rewards claimed through the {@code @lumen} command.
 *
 * A milestone is "available" once a character's level reaches the milestone
 * level and no claim row exists for it, so eligibility is derived purely from
 * current level: characters who passed a milestone before this shipped can
 * still claim it, and claiming is idempotent via the milestone_claims unique key.
 *
 * Levels 70 and 150 grant a weapon the player chooses with
 * {@code @lumen weapon <type> [reverse]} rather than auto-granting an item.
 */
public class MilestoneRewardService {
    private static final Logger log = LoggerFactory.getLogger(MilestoneRewardService.class);

    public static final int[] MILESTONES = {10, 30, 70, 120, 150, 200};
    private static final int MAPLE_WEAPON_MILESTONE = 70;
    private static final int TIMELESS_WEAPON_MILESTONE = 150;

    private static final int POWER_ELIXIR = 2000005;

    // Level 64 Maple weapon set, by player-facing weapon-type key.
    private static final Map<String, Integer> MAPLE_WEAPONS = new LinkedHashMap<>();
    // Level 120 Timeless weapon set (default for the level 150 choice).
    private static final Map<String, Integer> TIMELESS_WEAPONS = new LinkedHashMap<>();
    // Level 120 Reverse weapon set (chosen with the "reverse" keyword).
    private static final Map<String, Integer> REVERSE_WEAPONS = new LinkedHashMap<>();
    // Aliases mapping friendly words onto the canonical weapon-type keys.
    private static final Map<String, String> WEAPON_ALIASES = new LinkedHashMap<>();

    static {
        MAPLE_WEAPONS.put("1hsword", 1302064);
        MAPLE_WEAPONS.put("2hsword", 1402039);
        MAPLE_WEAPONS.put("1haxe", 1312032);
        MAPLE_WEAPONS.put("2haxe", 1412027);
        MAPLE_WEAPONS.put("1hmace", 1322054);
        MAPLE_WEAPONS.put("2hmace", 1422029);
        MAPLE_WEAPONS.put("dagger", 1332056);
        MAPLE_WEAPONS.put("wand", 1372034);
        MAPLE_WEAPONS.put("staff", 1382039);
        MAPLE_WEAPONS.put("spear", 1432040);
        MAPLE_WEAPONS.put("polearm", 1442051);
        MAPLE_WEAPONS.put("bow", 1452045);
        MAPLE_WEAPONS.put("crossbow", 1462040);
        MAPLE_WEAPONS.put("claw", 1472055);
        MAPLE_WEAPONS.put("knuckle", 1482022);
        MAPLE_WEAPONS.put("gun", 1492022);

        TIMELESS_WEAPONS.put("1hsword", 1302081);
        TIMELESS_WEAPONS.put("2hsword", 1402046);
        TIMELESS_WEAPONS.put("1haxe", 1312037);
        TIMELESS_WEAPONS.put("2haxe", 1412033);
        TIMELESS_WEAPONS.put("1hmace", 1322060);
        TIMELESS_WEAPONS.put("2hmace", 1422037);
        TIMELESS_WEAPONS.put("dagger", 1332073);
        TIMELESS_WEAPONS.put("wand", 1372044);
        TIMELESS_WEAPONS.put("staff", 1382057);
        TIMELESS_WEAPONS.put("spear", 1432047);
        TIMELESS_WEAPONS.put("polearm", 1442063);
        TIMELESS_WEAPONS.put("bow", 1452057);
        TIMELESS_WEAPONS.put("crossbow", 1462050);
        TIMELESS_WEAPONS.put("claw", 1472068);
        TIMELESS_WEAPONS.put("knuckle", 1482023);
        TIMELESS_WEAPONS.put("gun", 1492023);

        REVERSE_WEAPONS.put("1hsword", 1302086);
        REVERSE_WEAPONS.put("2hsword", 1402047);
        REVERSE_WEAPONS.put("1haxe", 1312038);
        REVERSE_WEAPONS.put("2haxe", 1412034);
        REVERSE_WEAPONS.put("1hmace", 1322061);
        REVERSE_WEAPONS.put("2hmace", 1422038);
        REVERSE_WEAPONS.put("dagger", 1332075);
        REVERSE_WEAPONS.put("wand", 1372045);
        REVERSE_WEAPONS.put("staff", 1382059);
        REVERSE_WEAPONS.put("spear", 1432049);
        REVERSE_WEAPONS.put("polearm", 1442067);
        REVERSE_WEAPONS.put("bow", 1452059);
        REVERSE_WEAPONS.put("crossbow", 1462051);
        REVERSE_WEAPONS.put("claw", 1472071);
        REVERSE_WEAPONS.put("knuckle", 1482024);
        REVERSE_WEAPONS.put("gun", 1492025);

        WEAPON_ALIASES.put("sword", "1hsword");
        WEAPON_ALIASES.put("axe", "1haxe");
        WEAPON_ALIASES.put("mace", "1hmace");
        WEAPON_ALIASES.put("blunt", "1hmace");
        WEAPON_ALIASES.put("xbow", "crossbow");
        WEAPON_ALIASES.put("knuckler", "knuckle");
        WEAPON_ALIASES.put("pole", "polearm");
    }

    public static boolean isMilestone(int level) {
        for (int m : MILESTONES) {
            if (m == level) {
                return true;
            }
        }
        return false;
    }

    private static boolean isWeaponMilestone(int milestone) {
        return milestone == MAPLE_WEAPON_MILESTONE || milestone == TIMELESS_WEAPON_MILESTONE;
    }

    /**
     * Fires the floating + chat notifications when a character crosses a milestone level.
     */
    public static void onLevelReached(Character chr, int level) {
        if (!isMilestone(level) || chr.isGM()) {
            return;
        }
        chr.showHint("#e#bMilestone reached!#k#n\r\nYou unlocked a #rLevel " + level + "#k reward.\r\nType #b@lumen#k to claim it!", 360);
        chr.yellowMessage("[Milestone] You reached Level " + level + "! A reward is waiting — claim it with @lumen.");
    }

    /**
     * {@code @lumen} with no arguments: claims every available auto-grant milestone
     * and tells the player how to pick weapons for any weapon-choice milestones.
     */
    public static void claimAvailable(Character chr) {
        List<Integer> available = getUnclaimed(chr);
        if (available.isEmpty()) {
            int next = nextMilestoneAbove(chr.getLevel());
            if (next > 0) {
                chr.yellowMessage("[Milestone] No rewards to claim right now. Your next milestone is Level " + next + ".");
            } else {
                chr.yellowMessage("[Milestone] You have claimed every milestone reward. Thanks for playing LumenMS!");
            }
            return;
        }

        boolean grantedAny = false;
        for (int milestone : available) {
            if (isWeaponMilestone(milestone)) {
                promptWeaponChoice(chr, milestone);
            } else if (grantAutoMilestone(chr, milestone)) {
                grantedAny = true;
            }
        }
        if (grantedAny) {
            chr.yellowMessage("[Milestone] Type @lumen again to check for any remaining rewards.");
        }
    }

    /**
     * {@code @lumen weapon <type> [reverse]}: grants the chosen weapon for the lowest
     * currently-available weapon-choice milestone (70 = Maple, 150 = Timeless/Reverse).
     */
    public static void claimWeapon(Character chr, String typeArg, boolean reverse) {
        int milestone = 0;
        for (int m : getUnclaimed(chr)) {
            if (isWeaponMilestone(m)) {
                milestone = m;
                break;
            }
        }
        if (milestone == 0) {
            chr.yellowMessage("[Milestone] You have no weapon reward to claim right now.");
            return;
        }

        String key = canonicalWeaponKey(typeArg);
        Map<String, Integer> set = milestone == MAPLE_WEAPON_MILESTONE
                ? MAPLE_WEAPONS
                : (reverse ? REVERSE_WEAPONS : TIMELESS_WEAPONS);
        Integer itemId = key == null ? null : set.get(key);
        if (itemId == null) {
            chr.yellowMessage("[Milestone] Unknown weapon type '" + typeArg + "'. Pick one of:");
            chr.yellowMessage(String.join(", ", set.keySet()));
            if (milestone == TIMELESS_WEAPON_MILESTONE) {
                chr.yellowMessage("Add 'reverse' for the Reverse variant, e.g. @lumen weapon bow reverse");
            }
            return;
        }

        if (!chr.canHold(itemId, 1)) {
            chr.yellowMessage("[Milestone] Make room in your Equip inventory first, then try again.");
            return;
        }
        if (!recordClaim(chr.getId(), milestone)) {
            chr.yellowMessage("[Milestone] That reward was already claimed.");
            return;
        }
        chr.getAbstractPlayerInteraction().gainItem(itemId, (short) 1, false, true);
        chr.yellowMessage("[Milestone] Level " + milestone + " weapon granted. Enjoy!");
    }

    private static void promptWeaponChoice(Character chr, int milestone) {
        if (milestone == MAPLE_WEAPON_MILESTONE) {
            chr.yellowMessage("[Milestone] Level 70: choose your Level 64 Maple weapon with @lumen weapon <type>");
            chr.yellowMessage("Types: " + String.join(", ", MAPLE_WEAPONS.keySet()));
        } else {
            chr.yellowMessage("[Milestone] Level 150: choose your Level 120 weapon with @lumen weapon <type> [reverse]");
            chr.yellowMessage("Types: " + String.join(", ", TIMELESS_WEAPONS.keySet()) + " (add 'reverse' for the Reverse set)");
        }
    }

    /**
     * Grants the fixed reward bundle for a milestone and records the claim. Returns
     * false (without recording) if the player lacks inventory space, so they can
     * free room and retry.
     */
    private static boolean grantAutoMilestone(Character chr, int milestone) {
        List<int[]> items = new ArrayList<>();   // {itemId, quantity}
        long mesos = 0;
        int fame = 0;
        int nx = 0;
        String summary;

        switch (milestone) {
            case 10 -> {
                items.add(new int[]{ItemId.WHITE_POTION, 300});
                items.add(new int[]{ItemId.MANA_ELIXIR, 300});
                items.add(new int[]{ItemId.PENDANT_OF_THE_SPIRIT, 1, (int) HOURS.toMillis(24)});
                summary = "300 White Potions, 300 Mana Elixirs and a 24-hour Pendant of the Spirit";
            }
            case 30 -> {
                items.add(new int[]{POWER_ELIXIR, 300});
                items.add(new int[]{ItemId.HYPER_TELEPORT_ROCK, 1});
                mesos = 1_000_000;
                summary = "300 Power Elixirs, a Hyper Teleport Rock and 1,000,000 mesos";
            }
            case 120 -> {
                items.add(new int[]{POWER_ELIXIR, 500});
                mesos = 10_000_000;
                summary = "500 Power Elixirs and 10,000,000 mesos";
            }
            case 200 -> {
                mesos = 100_000_000;
                fame = 100;
                nx = 150_000;
                summary = "100,000,000 mesos, +100 fame and 150,000 NX";
            }
            default -> {
                return false;
            }
        }

        for (int[] it : items) {
            if (!chr.canHold(it[0], it[1])) {
                chr.yellowMessage("[Milestone] Level " + milestone + ": free up inventory space, then type @lumen again to claim.");
                return false;
            }
        }
        if (!recordClaim(chr.getId(), milestone)) {
            return false;
        }

        for (int[] it : items) {
            long expires = it.length > 2 ? it[2] : -1;
            chr.getAbstractPlayerInteraction().gainItem(it[0], (short) it[1], false, true, expires);
        }
        if (mesos > 0) {
            chr.gainMeso((int) Math.min(mesos, Integer.MAX_VALUE), true, false, true);
        }
        if (fame > 0) {
            chr.gainFame(fame);
        }
        if (nx > 0) {
            chr.getCashShop().gainCash(1, nx); // 1 = NX_CREDIT
        }
        chr.yellowMessage("[Milestone] Level " + milestone + " reward claimed: " + summary + "!");
        return true;
    }

    /** Milestone levels the character has reached but not yet claimed, ascending. */
    public static List<Integer> getUnclaimed(Character chr) {
        TreeSet<Integer> claimed = loadClaimed(chr.getId());
        List<Integer> result = new ArrayList<>();
        int level = chr.getLevel();
        for (int m : MILESTONES) {
            if (level >= m && !claimed.contains(m)) {
                result.add(m);
            }
        }
        return result;
    }

    public static boolean hasUnclaimed(Character chr) {
        return !getUnclaimed(chr).isEmpty();
    }

    private static int nextMilestoneAbove(int level) {
        for (int m : MILESTONES) {
            if (m > level) {
                return m;
            }
        }
        return 0;
    }

    private static String canonicalWeaponKey(String arg) {
        if (arg == null) {
            return null;
        }
        String key = arg.toLowerCase();
        key = WEAPON_ALIASES.getOrDefault(key, key);
        return MAPLE_WEAPONS.containsKey(key) ? key : null;
    }

    private static TreeSet<Integer> loadClaimed(int characterId) {
        TreeSet<Integer> claimed = new TreeSet<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT milestone FROM milestone_claims WHERE characterId = ?")) {
            ps.setInt(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    claimed.add(rs.getInt("milestone"));
                }
            }
        } catch (Exception e) {
            log.error("Failed to load milestone claims for character {}", characterId, e);
        }
        return claimed;
    }

    /**
     * Inserts a claim row. Returns false if the row already existed (duplicate key)
     * or the insert failed, which guards against double-claiming under races.
     */
    private static boolean recordClaim(int characterId, int milestone) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "INSERT IGNORE INTO milestone_claims (characterId, milestone) VALUES (?, ?)")) {
            ps.setInt(1, characterId);
            ps.setInt(2, milestone);
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            log.error("Failed to record milestone claim ({}, {})", characterId, milestone, e);
            return false;
        }
    }
}
