/*
    LumenMS Rebirth Ring ("Soul Vessel") — a unique equip whose per-instance stats
    grow with every rebirth, weighted by which class the character was when they
    rebirthed. Persists across rebirths trivially: rebirth never touches the
    inventory (see service.RebirthService.performRebirth) so the equip just sits
    where it is. The stack counts that drive the stats live in the rebirth_ring
    table (changeSet 034) so the equip can be re-issued if ever lost.

    Hook point: RebirthService.performRebirth() calls engraveRebirth() AFTER the
    new rebirth count is saved and BEFORE saveCharToDB(), passing the job the
    character is leaving. saveCharToDB() then persists the mutated equip row.

    Formula (per stat, per class): total = round(base * sqrt(stacks)).
    Square-root scaling rewards specialization in a class (each extra stack
    still adds something) but with sharp diminishing returns (the 10th Warrior
    rebirth adds far less than the 1st).
*/
package service;

import client.Character;
import client.Job;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import constants.inventory.ItemConstants;
import tools.DatabaseConnection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class RebirthRingService {
    private static final Logger log = LoggerFactory.getLogger(RebirthRingService.class);

    /**
     * Item id used as the Soul Vessel visual. Defaults to "First Anniversary Ring"
     * (1112000), a generic ring present in stock v83 WZ. Swap to any unused ring id
     * in the 1112xxx range — the stat columns are written per-instance into
     * inventoryequipment so the WZ baseline is irrelevant once we engrave it.
     * (CARAT_RING_BASE 1112300 / WEDDING_RING_* 1112803-1112809 are taken.)
     */
    public static final int SOUL_VESSEL_ITEM_ID = 1112000;

    /** Per-class base stat values (multiplied by sqrt(stacks)). Tune freely. */
    private static final int WARRIOR_STR_BASE = 3;
    private static final int WARRIOR_HP_BASE = 60;
    private static final int MAGICIAN_INT_BASE = 3;
    private static final int MAGICIAN_MP_BASE = 60;
    private static final int BOWMAN_DEX_BASE = 3;
    private static final int BOWMAN_AVOID_BASE = 4;
    private static final int THIEF_LUK_BASE = 3;
    private static final int THIEF_SPEED_BASE = 2;
    private static final int PIRATE_STR_BASE = 2;
    private static final int PIRATE_DEX_BASE = 2;
    private static final int PIRATE_HP_BASE = 30;

    private RebirthRingService() {
    }

    /** Bucket for the in-DB stack counts for a single character. */
    public static final class Stacks {
        public final int warrior, magician, bowman, thief, pirate;
        public Stacks(int w, int m, int b, int t, int p) {
            this.warrior = w; this.magician = m; this.bowman = b;
            this.thief = t; this.pirate = p;
        }
        public int total() { return warrior + magician + bowman + thief + pirate; }
    }

    /** Computed stat totals to write onto the equip row. */
    public static final class RingStats {
        public final short str, dex, _int, luk, hp, mp, avoid, speed;
        public RingStats(int str, int dex, int _int, int luk, int hp, int mp, int avoid, int speed) {
            this.str = (short) str; this.dex = (short) dex;
            this._int = (short) _int; this.luk = (short) luk;
            this.hp = (short) hp; this.mp = (short) mp;
            this.avoid = (short) avoid; this.speed = (short) speed;
        }
    }

    // ---------------------------------------------------------------- public API

    /**
     * Engrave one new stack onto the character's Soul Vessel for the job they are
     * leaving. Increments the appropriate stack column, ensures the ring exists in
     * the player's possession (granting one to free EQUIP space on first call),
     * then recomputes and writes the ring's stats. The mutation lives on the
     * in-memory Equip object; the caller (RebirthService.performRebirth) is
     * expected to call saveCharToDB() afterward, which flushes it to the DB.
     *
     * Safe to call even if the player's inventory is full at first rebirth: the
     * stacks are still recorded, and the ring can be re-issued later from the
     * accumulated stack counts.
     */
    public static void engraveRebirth(Character chr, Job leavingJob, int newRebirthCount) {
        if (chr == null) {
            return;
        }
        int branch = branchOf(leavingJob);
        Stacks stacks = incrementStack(chr.getId(), branch);
        if (stacks == null) {
            return;
        }

        Equip ring = findRing(chr);
        boolean equipped = ring != null && isInEquipped(chr, ring);
        if (ring == null) {
            ring = tryGrantRing(chr);
            if (ring == null) {
                chr.yellowMessage("Your Soul Vessel could not be issued — your equip inventory is full. "
                        + "Free a slot and speak to the rebirth dialog to claim it.");
                return;
            }
        }

        RingStats s = computeStats(stacks);
        applyStats(ring, s, stacks.total());
        chr.forceUpdateItem(ring);

        // If the ring is currently worn, its new stats are sitting on the Equip object
        // but the player's local* stats were last recomputed before we mutated it.
        // equipChanged() runs the recalc + rebroadcast so the buff takes effect now.
        if (equipped) {
            chr.equipChanged();
        }
    }

    private static boolean isInEquipped(Character chr, Equip ring) {
        Inventory inv = chr.getInventory(InventoryType.EQUIPPED);
        if (inv == null) {
            return false;
        }
        for (Item it : inv.list()) {
            if (it == ring) {
                return true;
            }
        }
        return false;
    }

    /**
     * Re-issue / refresh a player's ring (e.g. an NPC dialog after the player lost
     * it). Reads stacks from the DB, grants if missing, then writes current stats.
     * Returns true if the ring is now in the player's possession.
     */
    public static boolean reissueRing(Character chr) {
        Stacks stacks = loadStacks(chr.getId());
        if (stacks.total() == 0) {
            return false; // never rebirthed; no ring to issue
        }
        Equip ring = findRing(chr);
        boolean equipped = ring != null && isInEquipped(chr, ring);
        if (ring == null) {
            ring = tryGrantRing(chr);
            if (ring == null) {
                return false;
            }
        }
        RingStats s = computeStats(stacks);
        applyStats(ring, s, stacks.total());
        chr.forceUpdateItem(ring);
        if (equipped) {
            chr.equipChanged();
        }
        return true;
    }

    public static Stacks getStacks(int characterId) {
        return loadStacks(characterId);
    }

    /**
     * Login rehydrate: re-applies the ring's stats from the rebirth_ring table on
     * top of the in-memory Equip, treating the DB stacks as the source of truth.
     *
     * Normal operation already persists the ring's stats through inventoryequipment
     * columns (saveCharToDB at the end of performRebirth), so this is mainly
     * defensive — it covers:
     *   - manual DB edits to inventoryequipment that diverged the equip from stacks
     *   - scrolls, !seteqstat, or any future mutation that touched the ring
     *   - a partial-write crash between incrementStack and saveCharToDB
     *
     * Idempotent: writes the same values that are already there if nothing
     * drifted, so calling it on every login is cheap and safe. Does NOT
     * increment any stack, does NOT grant a ring to players who don't have one.
     */
    public static void applyOnLogin(Character chr) {
        if (chr == null) {
            return;
        }
        Stacks stacks = loadStacks(chr.getId());
        if (stacks.total() == 0) {
            return; // never rebirthed
        }
        Equip ring = findRing(chr);
        if (ring == null) {
            return; // player rebirthed before but doesn't have the ring on this character (storage / lost / deleted)
        }
        boolean equipped = isInEquipped(chr, ring);

        RingStats s = computeStats(stacks);
        applyStats(ring, s, stacks.total());
        chr.forceUpdateItem(ring);
        if (equipped) {
            chr.equipChanged();
        }
    }

    public static RingStats previewStats(Stacks stacks) {
        return computeStats(stacks);
    }

    /**
     * Factory for NPC scripts so they don't have to instantiate the nested
     * Stacks class via reflection (which is awkward in Nashorn). Use this to
     * build hypothetical stack counts for a "what if I rebirthed now?" preview.
     */
    public static Stacks stacksOf(int warrior, int magician, int bowman, int thief, int pirate) {
        return new Stacks(warrior, magician, bowman, thief, pirate);
    }

    // ----------------------------------------------------------- math / branches

    /**
     * 1=Warrior 2=Magician 3=Bowman 4=Thief 5=Pirate, or 0 if the job doesn't map
     * to an explorer branch (beginners, Cygnus/Aran/Evan). Branch 0 increments
     * nothing — the rebirth still proceeds, just no engraving for that hop.
     */
    private static int branchOf(Job leavingJob) {
        if (leavingJob == null) {
            return 0;
        }
        int id = leavingJob.getId();
        int b = id / 100;
        if (b >= 1 && b <= 5) {
            return b;
        }
        // Cygnus 1xxx (e.g. 1100 Noblesse Warrior) — fold to explorer branch.
        if (id >= 1000 && id < 2000) {
            b = (id % 1000) / 100;
            if (b >= 1 && b <= 5) {
                return b;
            }
        }
        return 0;
    }

    private static RingStats computeStats(Stacks st) {
        int strV = scale(WARRIOR_STR_BASE, st.warrior) + scale(PIRATE_STR_BASE, st.pirate);
        int dexV = scale(BOWMAN_DEX_BASE, st.bowman) + scale(PIRATE_DEX_BASE, st.pirate);
        int intV = scale(MAGICIAN_INT_BASE, st.magician);
        int lukV = scale(THIEF_LUK_BASE, st.thief);
        int hpV = scale(WARRIOR_HP_BASE, st.warrior) + scale(PIRATE_HP_BASE, st.pirate);
        int mpV = scale(MAGICIAN_MP_BASE, st.magician);
        int avoidV = scale(BOWMAN_AVOID_BASE, st.bowman);
        int speedV = scale(THIEF_SPEED_BASE, st.thief);
        return new RingStats(strV, dexV, intV, lukV, hpV, mpV, avoidV, speedV);
    }

    /** Diminishing returns: round(base * sqrt(stacks)). Zero stacks = 0. */
    private static int scale(int base, int stacks) {
        if (stacks <= 0) {
            return 0;
        }
        return (int) Math.round(base * Math.sqrt(stacks));
    }

    // ------------------------------------------------------ equip ops (in-memory)

    private static Equip findRing(Character chr) {
        Equip eq = findRingIn(chr.getInventory(InventoryType.EQUIP));
        if (eq != null) {
            return eq;
        }
        return findRingIn(chr.getInventory(InventoryType.EQUIPPED));
    }

    private static Equip findRingIn(Inventory inv) {
        if (inv == null) {
            return null;
        }
        for (Item it : inv.list()) {
            if (it instanceof Equip && it.getItemId() == SOUL_VESSEL_ITEM_ID) {
                return (Equip) it;
            }
        }
        return null;
    }

    /** Grant a fresh ring to EQUIP inventory if the player has space. */
    private static Equip tryGrantRing(Character chr) {
        if (!chr.canHold(SOUL_VESSEL_ITEM_ID, 1)) {
            return null;
        }
        chr.getAbstractPlayerInteraction().gainItem(SOUL_VESSEL_ITEM_ID, (short) 1, false, true);
        return findRing(chr);
    }

    /**
     * Overwrite the equip's mutable stats and engrave the tier into the owner
     * field. The owner field renders in the in-game tooltip ("Owner: ...") and is
     * the easiest tier indicator that doesn't require new WZ entries; swap to a
     * per-tier item id if you ever add proper WZ icons.
     */
    private static void applyStats(Equip ring, RingStats s, int totalStacks) {
        ring.setStr(s.str);
        ring.setDex(s.dex);
        ring.setInt(s._int);
        ring.setLuk(s.luk);
        ring.setHp(s.hp);
        ring.setMp(s.mp);
        ring.setAvoid(s.avoid);
        ring.setSpeed(s.speed);

        ring.setOwner("Vessel " + toRoman(totalStacks));

        short flag = ring.getFlag();
        flag |= ItemConstants.UNTRADEABLE;
        flag |= ItemConstants.LOCK;   // prevents accidental drop/move; ring is meant to be permanent
        ring.setFlag(flag);
    }

    private static String toRoman(int n) {
        if (n <= 0) return "[0]";
        // Soul Vessel rebirths well within roman-numeral comfort; clamp at 39 for sanity.
        String[] tens = {"", "X", "XX", "XXX"};
        String[] ones = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX"};
        int cap = Math.min(n, 39);
        return "[" + tens[cap / 10] + ones[cap % 10] + "]";
    }

    // -------------------------------------------------------------- DB persistence

    private static Stacks loadStacks(int characterId) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT warrior_stacks, magician_stacks, bowman_stacks, thief_stacks, pirate_stacks "
                             + "FROM rebirth_ring WHERE characterid = ?")) {
            ps.setInt(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new Stacks(
                            rs.getInt("warrior_stacks"),
                            rs.getInt("magician_stacks"),
                            rs.getInt("bowman_stacks"),
                            rs.getInt("thief_stacks"),
                            rs.getInt("pirate_stacks"));
                }
            }
        } catch (SQLException ex) {
            log.warn("rebirth_ring loadStacks failed for chr {}", characterId, ex);
        }
        return new Stacks(0, 0, 0, 0, 0);
    }

    /**
     * Increment one column for the given branch and return the resulting row.
     * If the branch is 0 (unmapped job), returns the current stacks without
     * incrementing — caller may still want them to refresh the ring.
     */
    private static Stacks incrementStack(int characterId, int branch) {
        String col = columnForBranch(branch);
        if (col == null) {
            return loadStacks(characterId);
        }
        // UPSERT: insert a fresh row with this branch at 1 if absent, else +1 it.
        String sql = "INSERT INTO rebirth_ring (characterid, " + col + ") VALUES (?, 1) "
                + "ON DUPLICATE KEY UPDATE " + col + " = " + col + " + 1";
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            ps.executeUpdate();
        } catch (SQLException ex) {
            log.warn("rebirth_ring incrementStack failed for chr {} branch {}", characterId, branch, ex);
            return null;
        }
        return loadStacks(characterId);
    }

    private static String columnForBranch(int branch) {
        switch (branch) {
            case 1: return "warrior_stacks";
            case 2: return "magician_stacks";
            case 3: return "bowman_stacks";
            case 4: return "thief_stacks";
            case 5: return "pirate_stacks";
            default: return null;
        }
    }
}
