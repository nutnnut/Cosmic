package server.bots;

import client.Character;
import client.Job;
import client.inventory.manipulator.InventoryManipulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static void grantStarterKitIfEligible(Character bot, Job oldJob, Job newJob) {
        if (!isFirstJobAdvancement(oldJob, newJob)) {
            return;
        }

        List<ItemGrant> starterKit = starterKitFor(newJob);
        if (starterKit.isEmpty()) {
            return;
        }
        // Grant each item independently so the weapon (first entry in every kit) still
        // lands even if the bot lacks room for the bulk ammo / throwing items.
        for (ItemGrant grant : starterKit) {
            if (!InventoryManipulator.addById(bot.getClient(), grant.itemId(), grant.quantity())) {
                log.warn("Bot '{}' could not receive starter item {} x{} for job {} (no inventory space)",
                        bot.getName(), grant.itemId(), grant.quantity(), newJob);
            }
        }
    }

    private static ItemGrant grant(int itemId, int quantity) {
        return new ItemGrant(itemId, (short) quantity);
    }
}
