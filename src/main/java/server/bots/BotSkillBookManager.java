package server.bots;

import client.Character;
import client.processor.stat.SkillBookProcessor;
import client.inventory.InventoryType;
import client.inventory.Item;
import constants.inventory.ItemConstants;
import server.ItemInformationProvider;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Autonomous fourth-job skill/mastery-book demand and use. */
final class BotSkillBookManager {
    @FunctionalInterface
    interface WantedNowLookup {
        boolean wanted(BotEntry entry, Character bot, int itemId);
    }

    @FunctionalInterface
    interface BookUse {
        SkillBookProcessor.Result use(Character bot, short slot, int itemId);
    }

    static WantedNowLookup wantedNow = BotSkillBookManager::canUseWantedBookNow;
    static BookUse bookUse = SkillBookProcessor::useSkillBook;
    static java.util.function.BiConsumer<BotEntry, Character> assignSp = BotBuildManager::autoAssignSp;

    private BotSkillBookManager() {
    }

    static boolean isSkillBook(int itemId) {
        return ItemConstants.isSkillBook(itemId);
    }

    /** A book is wanted when it raises a skill in this bot's chosen build, even before its
     * reqSkillLevel is reached. This keeps future Mastery 30 books from being sold or given away. */
    static boolean wantsBook(BotEntry entry, Character bot, int itemId) {
        if (entry == null || bot == null || !isSkillBook(itemId)) {
            return false;
        }
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        Map<String, Integer> stats = ii.getSkillStats(itemId, bot.getJob().getId());
        if (stats == null) {
            return false;
        }
        int skillId = stats.getOrDefault("skillid", 0);
        client.Skill skill = client.SkillFactory.getSkill(skillId);
        if (skillId <= 0 || skill == null) {
            return false;
        }
        int plannedTarget = BotBuildManager.plannedSkillTarget(entry, bot, skillId);
        int currentCap = bot.getMasterLevel(skill);
        int bookCap = stats.getOrDefault("masterLevel", 0);
        return raisesPlannedCap(skillId, Math.min(plannedTarget, bookCap), currentCap);
    }

    static boolean canUseWantedBookNow(BotEntry entry, Character bot, int itemId) {
        return wantsBook(entry, bot, itemId)
                && ItemInformationProvider.getInstance().canUseSkillBook(bot, itemId);
    }

    static boolean needsBookAcquisition(BotEntry entry, Character bot, int itemId) {
        return wantsBook(entry, bot, itemId)
                && bot.getInventory(InventoryType.USE).countById(itemId) <= 0;
    }

    static boolean isMissingAnyWantedBook(BotEntry entry, Character bot) {
        for (int id = ItemConstants.SKILL_BOOK_FIRST; id <= ItemConstants.SKILL_BOOK_LAST; id++) {
            if (needsBookAcquisition(entry, bot, id)) {
                return true;
            }
        }
        for (int id = ItemConstants.MASTERY_BOOK_FIRST; id <= ItemConstants.MASTERY_BOOK_LAST; id++) {
            if (needsBookAcquisition(entry, bot, id)) {
                return true;
            }
        }
        return false;
    }

    static boolean raisesPlannedCap(int skillId, int plannedTarget, int currentCap) {
        return skillId > 0 && plannedTarget > currentCap;
    }

    /** Progress weight for the grind planner; includes the book's own application chance. */
    static double needFraction(BotEntry entry, Character bot, int itemId) {
        if (!needsBookAcquisition(entry, bot, itemId)) {
            return 0.0;
        }
        Map<String, Integer> stats = ItemInformationProvider.getInstance()
                .getSkillStats(itemId, bot.getJob().getId());
        int skillId = stats.getOrDefault("skillid", 0);
        int currentCap = bot.getMasterLevel(client.SkillFactory.getSkill(skillId));
        int plannedTarget = BotBuildManager.plannedSkillTarget(entry, bot, skillId);
        return needFraction(stats.getOrDefault("success", 0),
                stats.getOrDefault("masterLevel", 0), currentCap, plannedTarget);
    }

    static double needFraction(int successPercent, int bookMasterLevel,
                               int currentCap, int plannedTarget) {
        int unlockedLevels = Math.max(1, Math.min(plannedTarget, bookMasterLevel) - currentCap);
        double success = Math.clamp(successPercent / 100.0, 0.0, 1.0);
        return success * Math.min(0.60, 0.20 + unlockedLevels / 60.0);
    }

    static void tick(BotEntry entry, Character bot, long nowMs) {
        if (entry == null || bot == null || nowMs < entry.nextSkillBookUseAtMs
                || bot.getTrade() != null || entry.marketBusy) {
            return;
        }
        Item book = null;
        for (Item item : bot.getInventory(InventoryType.USE).list()) {
            if (wantedNow.wanted(entry, bot, item.getItemId())) {
                book = item;
                break;
            }
        }
        if (book == null) {
            entry.nextSkillBookUseAtMs = nowMs + 15_000L;
            return;
        }

        // One book at a time with a small human-like pause before another attempt.
        entry.nextSkillBookUseAtMs = nowMs + ThreadLocalRandom.current().nextLong(2_500L, 6_501L);
        SkillBookProcessor.Result result = bookUse.use(
                bot, book.getPosition(), book.getItemId());
        if (result != null && result.success()) {
            assignSp.accept(entry, bot);
        }
    }
}
