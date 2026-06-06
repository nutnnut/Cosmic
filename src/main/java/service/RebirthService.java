/*
    LumenMS rebirth mechanic.

    Shared logic behind the @rebirth command and the scripts/npc/rebirth.js dialog.
    A character at REBIRTH_LEVEL can be reborn: they keep two skills of their choice
    from their current class, choose a new (first-job) class to restart as, drop back
    to REBORN_LEVEL, and receive AP_PER_REBIRTH AP for every rebirth they have done
    (rebirth #1 -> 750 AP, #4 -> 3000 AP). The running count persists in the
    character_rebirths table (changeSet 032).
*/
package service;

import client.Character;
import client.Character.SkillEntry;
import client.Job;
import client.Skill;
import client.SkillFactory;
import client.keybind.KeyBinding;
import config.YamlConfig;
import provider.Data;
import provider.DataProviderFactory;
import provider.wz.WZFiles;
import server.StatEffect;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;

public class RebirthService {
    public static final int REBIRTH_LEVEL = 200;
    public static final int AP_PER_REBIRTH = 750;
    private static final int REBORN_LEVEL = 10;
    private static final int BASE_STAT = 4;
    private static final int KEYBIND_TYPE_SKILL = 1;       // client keymap: type 1 == skill, action == skillId

    private RebirthService() {
    }

    public static boolean isEligible(Character chr) {
        return chr != null && chr.getLevel() >= REBIRTH_LEVEL;
    }

    /**
     * Skill ids the player may pick two of for THIS rebirth: learned (level > 0) skills
     * that belong to the player's current job line (decided by the skill id's job prefix),
     * are not already banked from a previous rebirth, and are castable (not passive).
     * Passive skills (masteries, crit, Final Attack, etc.) are excluded because their
     * effects are tied to the matching job and would not function after a class change.
     */
    public static int[] keepableSkillIds(Character chr) {
        Set<Integer> banked = loadBankedSkillIds(chr.getId());
        Job job = chr.getJob();
        List<Integer> ids = new ArrayList<>();
        for (Entry<Skill, SkillEntry> e : chr.getSkills().entrySet()) {
            Skill skill = e.getKey();
            int id = skill.getId();
            if (e.getValue().skillevel > 0 && belongsToJob(job, id) && !banked.contains(id)
                    && isCastable(skill, e.getValue().skillevel)) {
                ids.add(id);
            }
        }
        int[] arr = new int[ids.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = ids.get(i);
        }
        return arr;
    }

    /** True if the skill belongs to the player's current job line (by skill-id job prefix). */
    private static boolean belongsToJob(Job job, int skillId) {
        Job skillJob = Job.getById(skillId / 10000);
        return skillJob != null && job.isA(skillJob);
    }

    /**
     * A skill is castable (active attack or activatable buff) rather than passive if it has
     * an attack action or grants buff stat-ups. Passive skills have neither, so they are
     * filtered out of the rebirth choices.
     */
    private static boolean isCastable(Skill skill, int level) {
        if (skill.getAction()) {
            return true;
        }
        int maxLevel = skill.getMaxLevel();
        if (maxLevel < 1) {
            return false;
        }
        int lookup = Math.min(Math.max(level, 1), maxLevel);
        StatEffect effect = skill.getEffect(lookup);
        return effect != null && !effect.getStatups().isEmpty();
    }

    /** Friendly label for a skill, e.g. "Power Strike (Lv.20)". */
    public static String skillLabel(Character chr, int skillId) {
        String name = SkillFactory.getSkillName(skillId);
        if (name == null || name.isEmpty()) {
            name = "Skill " + skillId;
        }
        Skill skill = SkillFactory.getSkill(skillId);
        int level = skill != null ? chr.getSkillLevel(skill) : 0;
        return name + " (Lv." + level + ")";
    }

    /** Plain name for a skill the player may not have learned yet. */
    public static String skillName(int skillId) {
        String name = SkillFactory.getSkillName(skillId);
        return (name == null || name.isEmpty()) ? ("Skill " + skillId) : name;
    }

    // ----- Pre-rebirth "taster": a one-time free skill from any explorer class -----

    /**
     * The taster is offered once to a character who has never been reborn and never
     * claimed it (no banked skills yet). It grants a free castable skill from a chosen
     * explorer class as a power boost and a preview of rebirth's cross-class skill keeping.
     */
    public static boolean tasterAvailable(Character chr) {
        return getRebirthCount(chr.getId()) == 0 && loadBankedSkillIds(chr.getId()).isEmpty();
    }

    /**
     * Castable skills from the given explorer base class (100/200/300/400/500) that the
     * player does not already have. Used to populate the taster skill menu.
     */
    public static int[] tasterSkillIds(Character chr, int baseJobId) {
        int classGroup = baseJobId / 100; // 1..5 for Warrior..Pirate
        List<Integer> ids = new ArrayList<>();
        for (Data sd : DataProviderFactory.getDataProvider(WZFiles.STRING).getData("Skill.img").getChildren()) {
            int skillId;
            try {
                skillId = Integer.parseInt(sd.getName());
            } catch (NumberFormatException nfe) {
                continue;
            }
            int skillJob = skillId / 10000;
            if (skillJob < 100 || skillJob >= 1000) {
                continue; // explorer jobs only (excludes beginner and Cygnus/Aran/Evan)
            }
            if (skillJob / 100 != classGroup) {
                continue; // not this class branch
            }
            Skill skill = SkillFactory.getSkill(skillId);
            if (skill == null || skill.getMaxLevel() < 1) {
                continue;
            }
            if (!isCastable(skill, skill.getMaxLevel())) {
                continue; // passives are excluded
            }
            if (chr.getSkillLevel(skill) > 0) {
                continue; // already known
            }
            ids.add(skillId);
        }
        Collections.sort(ids);
        int[] arr = new int[ids.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = ids.get(i);
        }
        return arr;
    }

    /** Grant a taster skill at max level, bank it (so it persists and the taster is consumed). */
    public static void grantTasterSkill(Character chr, int skillId) {
        Skill skill = SkillFactory.getSkill(skillId);
        if (skill == null) {
            return;
        }
        int maxLevel = skill.getMaxLevel();
        chr.changeSkillLevel(skill, (byte) maxLevel, maxLevel, -1);
        bankSkill(chr.getId(), skillId);
        chr.saveCharToDB();
    }

    public static int getRebirthCount(int characterId) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT rebirths FROM character_rebirths WHERE characterid = ?")) {
            ps.setInt(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("rebirths");
                }
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
        return 0;
    }

    private static void saveRebirthCount(int characterId, int count) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "INSERT INTO character_rebirths (characterid, rebirths) VALUES (?, ?) "
                             + "ON DUPLICATE KEY UPDATE rebirths = ?")) {
            ps.setInt(1, characterId);
            ps.setInt(2, count);
            ps.setInt(3, count);
            ps.executeUpdate();
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Perform the rebirth: drop to REBORN_LEVEL, change into targetJobId, wipe every
     * skill except keepSkill1/keepSkill2 (preserved at their learned levels), reset base
     * stats and grant (rebirths * AP_PER_REBIRTH) AP. Returns the AP granted.
     */
    public static int performRebirth(Character chr, int keepSkill1, int keepSkill2, int targetJobId) {
        // The kept set is every previously-banked skill (they persist automatically)
        // plus the two new picks from the current job.
        Set<Integer> keptSet = loadBankedSkillIds(chr.getId());
        keptSet.add(keepSkill1);
        keptSet.add(keepSkill2);

        int newCount = getRebirthCount(chr.getId()) + 1;
        int grantedAp = AP_PER_REBIRTH * newCount;

        // Capture the job the character is leaving BEFORE changeJob() overwrites it.
        // The Soul Vessel engrave (below) keys its stat bonus off this branch.
        Job leavingJob = chr.getJob();

        // Drop back to the reborn level (mirrors LevelCommand's level-set flow).
        chr.loseExp(chr.getExp(), false, false);
        chr.setLevel(REBORN_LEVEL - 1);
        chr.resetPlayerRates();
        if (YamlConfig.config.server.USE_ADD_RATES_BY_LEVEL) {
            chr.setPlayerRates();
        }
        chr.setWorldRates();
        chr.levelUp(false);

        // Change into the chosen class. changeJob() grants its own AP/SP, so this must
        // happen BEFORE we set the final AP total below.
        Job target = Job.getById(targetJobId);
        if (target != null) {
            chr.changeJob(target);
        }

        // Wipe every skill that is not in the kept set (iterate a copy to avoid concurrent
        // modification). Kept skills are left untouched at their current levels, so all
        // previously-banked rebirth skills carry through.
        for (Skill skill : new ArrayList<>(chr.getSkills().keySet())) {
            if (keptSet.contains(skill.getId())) {
                continue;
            }
            chr.changeSkillLevel(skill, (byte) 0, skill.getMaxLevel(), -1);
        }

        // Reset base stats and grant the cumulative rebirth AP to redistribute.
        chr.updateStrDexIntLuk(BASE_STAT);
        chr.changeRemainingAp(grantedAp, false);

        saveRebirthCount(chr.getId(), newCount);
        bankSkills(chr.getId(), keepSkill1, keepSkill2);

        // Engrave the rebirth onto the Soul Vessel ring (granted on first call if
        // the player has equip space). Mutates the equip's in-memory stat fields;
        // the saveCharToDB() below flushes them into inventoryequipment.
        RebirthRingService.engraveRebirth(chr, leavingJob, newCount);

        chr.saveCharToDB();
        return grantedAp;
    }

    /** Skills already banked from previous rebirths (carry through every rebirth). */
    public static Set<Integer> loadBankedSkillIds(int characterId) {
        Set<Integer> ids = new HashSet<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT skillid FROM character_rebirth_skills WHERE characterid = ?")) {
            ps.setInt(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getInt("skillid"));
                }
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
        return ids;
    }

    private static void bankSkills(int characterId, int skillId1, int skillId2) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "INSERT IGNORE INTO character_rebirth_skills (characterid, skillid) VALUES (?, ?)")) {
            ps.setInt(1, characterId);
            ps.setInt(2, skillId1);
            ps.addBatch();
            ps.setInt(1, characterId);
            ps.setInt(2, skillId2);
            ps.addBatch();
            ps.executeBatch();
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }

    private static void bankSkill(int characterId, int skillId) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "INSERT IGNORE INTO character_rebirth_skills (characterid, skillid) VALUES (?, ?)")) {
            ps.setInt(1, characterId);
            ps.setInt(2, skillId);
            ps.executeUpdate();
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Rebirth skills the player can rebind to a hotkey: every banked skill (taster pick
     * plus all skills kept across previous rebirths) that the character currently has
     * learned. These persist independently of level, so rebinding stays available even
     * below the rebirth level.
     */
    public static int[] rebindableSkillIds(Character chr) {
        List<Integer> ids = new ArrayList<>();
        for (int id : loadBankedSkillIds(chr.getId())) {
            Skill skill = SkillFactory.getSkill(id);
            if (skill != null && chr.getSkillLevel(skill) > 0) {
                ids.add(id);
            }
        }
        Collections.sort(ids);
        int[] arr = new int[ids.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = ids.get(i);
        }
        return arr;
    }

    /** Bind a kept skill to a keyboard key (client keymap type 1 == skill). */
    public static void bindHotkey(Character chr, int skillId, int keyCode) {
        chr.changeKeybinding(keyCode, new KeyBinding(KEYBIND_TYPE_SKILL, skillId));
        chr.sendKeymap();
        chr.saveCharToDB();   // persist immediately so the binding survives relog
    }
}
