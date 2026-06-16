package server.bots;

import client.Character;
import client.Job;
import client.inventory.InventoryType;
import client.inventory.manipulator.InventoryManipulator;
import constants.inventory.ItemConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

final class BotStarterKitManager {
    private static final Logger log = LoggerFactory.getLogger(BotStarterKitManager.class);

    record ItemGrant(int itemId, short quantity) {}

    private static final int BEGINNER_WARRIOR_SWORD = 1302077;
    private static final int BEGINNER_MAGICIAN_WAND = 1372043;
    private static final int BEGINNER_BOWMANS_BOW = 1452051;
    private static final int BEGINNER_THIEF_WRIST_GUARD = 1472061;
    private static final int BEGINNER_THIEF_SHORT_SWORD = 1332063;
    private static final int NOVA_THROWING_KNIVES = 2070015;
    private static final int GARNIER = 1492000;
    private static final int STEEL_KNUCKLER = 1482000;
    private static final int WOODEN_ARROWS = 2060000;
    private static final int BULLETS = 2330000;

    private static final Map<Job, List<ItemGrant>> FIRST_JOB_KITS = Map.of(
            Job.WARRIOR, List.of(grant(BEGINNER_WARRIOR_SWORD, 1)),
            Job.MAGICIAN, List.of(grant(BEGINNER_MAGICIAN_WAND, 1)),
            Job.BOWMAN, List.of(grant(BEGINNER_BOWMANS_BOW, 1), grant(WOODEN_ARROWS, 1000)),
            Job.THIEF, List.of(
                    grant(BEGINNER_THIEF_WRIST_GUARD, 1),
                    grant(BEGINNER_THIEF_SHORT_SWORD, 1),
                    grant(NOVA_THROWING_KNIVES, 500)
            ),
            Job.PIRATE, List.of(
                    grant(GARNIER, 1),
                    grant(STEEL_KNUCKLER, 1),
                    grant(BULLETS, 1000)
            )
    );

    static void advanceJob(BotEntry entry, Job newJob) {
        Character bot = entry.bot;
        Character owner = entry.owner;
        Job oldJob = bot.getJob();
        bot.changeJob(newJob);
        BotBuildManager.handleJobAdvance(entry, bot, oldJob, newJob);
        grantStarterKitIfEligible(bot, oldJob, newJob);
        BotEquipManager.autoEquip(bot, owner, null);
        BotChatManager.checkBotStatus(entry, bot);
    }

    static List<ItemGrant> starterKitFor(Job job) {
        return FIRST_JOB_KITS.getOrDefault(job, List.of());
    }

    static boolean isFirstJobAdvancement(Job oldJob, Job newJob) {
        return oldJob == Job.BEGINNER && FIRST_JOB_KITS.containsKey(newJob);
    }

    // Explorer job ids run base -> 1st(X00) -> 2nd(X10/X20/X30) -> 3rd(+1) -> 4th(+1) within each
    // branch (see client.Job), so the single deterministic successor of a 2nd/3rd job is just id+1.
    // This is the autopilot counterpart to the owner-typed alias parse in BotChatManager (3rd/4th
    // job have no choice, unlike 1st/2nd), and is restricted to explorer ids so Cygnus/Aran/Evan
    // (different numbering) never match.
    private static final int EXPLORER_MIN = 100;
    private static final int EXPLORER_MAX = 600;

    /** The lone 3rd-job successor of an explorer 2nd job (e.g. FIGHTER -> CRUSADER), or null if the
     *  given job is not an explorer 2nd job. */
    static Job thirdJobOf(Job secondJob) {
        int id = secondJob == null ? 0 : secondJob.getId();
        boolean isSecond = id >= EXPLORER_MIN && id < EXPLORER_MAX && id % 100 != 0 && id % 10 == 0;
        return isSecond ? Job.getById(id + 1) : null;
    }

    /** The lone 4th-job successor of an explorer 3rd job (e.g. CRUSADER -> HERO), or null if the
     *  given job is not an explorer 3rd job. */
    static Job fourthJobOf(Job thirdJob) {
        int id = thirdJob == null ? 0 : thirdJob.getId();
        boolean isThird = id >= EXPLORER_MIN && id < EXPLORER_MAX && id % 10 == 1;
        return isThird ? Job.getById(id + 1) : null;
    }

    // Job-topology SSOT for the autonomous (ownerless) job picker in BotBuildManager. Unlike the
    // deterministic 3rd/4th successors above, the 1st and 2nd advancements are real choices, so
    // these mirror the option sets the owner is offered in BotBuildManager.buildJobPrompt.

    /** The five explorer 1st-job classes an ownerless Beginner can advance into at lv10. */
    static List<Job> firstJobChoices() {
        return List.of(Job.WARRIOR, Job.MAGICIAN, Job.BOWMAN, Job.THIEF, Job.PIRATE);
    }

    /** The 2nd-job options for an explorer 1st job (lv30 choice), or empty for anything else. */
    static List<Job> secondJobChoices(Job firstJob) {
        if (firstJob == null) {
            return List.of();
        }
        return switch (firstJob) {
            case WARRIOR -> List.of(Job.FIGHTER, Job.PAGE, Job.SPEARMAN);
            case MAGICIAN -> List.of(Job.FP_WIZARD, Job.IL_WIZARD, Job.CLERIC);
            case BOWMAN -> List.of(Job.HUNTER, Job.CROSSBOWMAN);
            case THIEF -> List.of(Job.ASSASSIN, Job.BANDIT);
            case PIRATE -> List.of(Job.BRAWLER, Job.GUNSLINGER);
            default -> List.of();
        };
    }

    private static void grantStarterKitIfEligible(Character bot, Job oldJob, Job newJob) {
        if (!isFirstJobAdvancement(oldJob, newJob)) {
            return;
        }

        List<ItemGrant> starterKit = starterKitFor(newJob);
        if (starterKit.isEmpty()) {
            return;
        }
        if (!canHoldStarterKit(bot, starterKit)) {
            log.warn("Bot '{}' could not receive {} starter kit due to inventory space", bot.getName(), newJob);
            return;
        }

        for (ItemGrant grant : starterKit) {
            if (!InventoryManipulator.addById(bot.getClient(), grant.itemId(), grant.quantity())) {
                log.warn("Bot '{}' failed to receive starter item {} x{} for job {}",
                        bot.getName(), grant.itemId(), grant.quantity(), newJob);
            }
        }
    }

    private static boolean canHoldStarterKit(Character bot, List<ItemGrant> starterKit) {
        Map<InventoryType, Integer> requiredSlots = new EnumMap<>(InventoryType.class);
        for (ItemGrant grant : starterKit) {
            InventoryType inventoryType = ItemConstants.getInventoryType(grant.itemId());
            if (inventoryType == InventoryType.EQUIP) {
                requiredSlots.merge(InventoryType.EQUIP, 1, Integer::sum);
                continue;
            }
            if (!bot.canHold(grant.itemId(), grant.quantity())) {
                return false;
            }
        }

        for (Map.Entry<InventoryType, Integer> requirement : requiredSlots.entrySet()) {
            if (bot.getInventory(requirement.getKey()).getNumFreeSlot() < requirement.getValue()) {
                return false;
            }
        }
        return true;
    }

    private static ItemGrant grant(int itemId, int quantity) {
        return new ItemGrant(itemId, (short) quantity);
    }
}
