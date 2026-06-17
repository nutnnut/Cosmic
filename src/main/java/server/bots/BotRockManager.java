package server.bots;

import client.Character;
import client.Skill;
import client.SkillFactory;
import client.inventory.Item;
import constants.inventory.ItemConstants;
import server.StatEffect;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rock supply for bots whose buffs consume a rock per cast (Shadow Partner = Summoning Rock, mage
 * summons = Magic Rock, etc.). Which rock a skill consumes is read dynamically off its
 * {@link StatEffect#getItemCon()} / {@link StatEffect#getItemConNo()} — never hardcoded by skill id.
 * Mirrors {@link BotAmmoManager}: request from the best party donor when low, and the cast-side
 * sparing gate lives in {@link BotCombatManager#rockBuffWorthCasting}.
 */
final class BotRockManager {
    private static final Map<String, Long> rockShareBackoffUntil = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> rockShareCooldownUntil = new ConcurrentHashMap<>();

    private static final List<String> ROCK_REQUEST_MSGS = List.of(
            "low on rocks, anyone have spare?",
            "need summoning rocks soon, anyone got extras?",
            "running low on rocks, can someone share?");
    private static final List<String> ROCK_OFFER_MSGS = List.of(
            "i have spare rocks, inv u",
            "got some rocks for you, trading",
            "i can spare rocks, one sec");

    private BotRockManager() {}

    /** Rock item ids this bot's castable buff skills consume right now (dynamic, via itemCon). */
    static Set<Integer> neededRockIds(BotEntry entry, Character bot) {
        Set<Integer> ids = new LinkedHashSet<>();
        for (int skillId : entry.buffSkillIds) {
            Skill skill = SkillFactory.getSkill(skillId);
            if (skill == null) {
                continue;
            }
            int lvl = bot.getSkillLevel(skill);
            if (lvl <= 0) {
                continue;
            }
            StatEffect fx = skill.getEffect(lvl);
            if (fx != null && fx.getItemConNo() > 0 && fx.getItemCon() > 0) {
                ids.add(fx.getItemCon());
            }
        }
        return ids;
    }

    static int countRocks(Character bot, int rockId) {
        if (rockId <= 0) {
            return 0;
        }
        int total = 0;
        for (Item item : bot.getInventory(ItemConstants.getInventoryType(rockId)).list()) {
            if (item.getItemId() == rockId) {
                total += item.getQuantity();
            }
        }
        return total;
    }

    static void tickRockShareCheck(BotEntry entry, Character bot) {
        Set<Integer> rockIds = neededRockIds(entry, bot);
        if (rockIds.isEmpty()) {
            entry.rockShareRequested = false;
            return;
        }
        for (int rockId : rockIds) {
            if (countRocks(bot, rockId) < BotCombatManager.cfg.ROCK_LOW_WARN) {
                if ((!entry.rockShareRequested) && requestRockShare(entry, bot, rockId, false)) {
                    entry.rockShareRequested = true;
                }
                return;
            }
        }
        entry.rockShareRequested = false;
    }

    static void checkRockShareOnModeStart(BotEntry entry, Character bot) {
        entry.rockShareRequested = false;
        tickRockShareCheck(entry, bot);
    }

    static boolean requestRockShare(BotEntry entry, Character bot, int rockId, boolean bypassShareLimits) {
        Character owner = entry.owner;
        if (owner == null || owner == bot || bot.getTrade() != null || entry.pendingTradeCategory != null) {
            return false;
        }
        if (countRocks(bot, rockId) >= BotCombatManager.cfg.ROCK_LOW_WARN) {
            return false;
        }

        long now = System.currentTimeMillis();
        String backoffKey = owner.getId() + ":" + rockId;
        if (!bypassShareLimits) {
            if (now < rockShareBackoffUntil.getOrDefault(backoffKey, 0L)) {
                return false;
            }
            if (now < rockShareCooldownUntil.getOrDefault(owner.getId(), 0L)) {
                return false;
            }
            rockShareCooldownUntil.put(owner.getId(), now + 30_000L);
        }

        BotAutopilotManager.noteLowSupplyPartyRequest(entry);
        saySupplyRequest(bot, BotManager.randomReply(ROCK_REQUEST_MSGS));

        RockDonorPlan plan = selectRockDonor(owner.getId(), bot.getMapId(), entry, rockId);
        if (plan == null) {
            if (!bypassShareLimits) {
                rockShareBackoffUntil.put(backoffKey, now + 10 * 60_000L);
            }
            return true;
        }

        scheduleRockShare(plan, bot, rockId, BotManager.randMs(2000, 3000));
        return true;
    }

    private static RockDonorPlan selectRockDonor(int ownerId, int mapId, BotEntry excludedEntry, int rockId) {
        RockDonorPlan best = null;
        for (BotEntry sibling : BotManager.getInstance().getBotEntries(ownerId)) {
            if (sibling == excludedEntry || sibling.bot == null || sibling.bot.getMapId() != mapId) {
                continue;
            }
            Character donorBot = sibling.bot;
            int count = countRocks(donorBot, rockId);
            if (count < BotCombatManager.cfg.ROCK_LOW_WARN) {
                continue;
            }
            // A donor that also consumes this rock keeps a working buffer; a non-user can give it all.
            boolean donorNeedsSameRock = neededRockIds(sibling, donorBot).contains(rockId);
            int donationQty = donorNeedsSameRock
                    ? (count - BotCombatManager.cfg.ROCK_LOW_WARN) / 2
                    : count;
            if (donationQty <= 0) {
                continue;
            }
            RockDonorPlan candidate = new RockDonorPlan(sibling, count, donorNeedsSameRock, donationQty);
            if (isBetterDonor(candidate, best)) {
                best = candidate;
            }
        }
        return best;
    }

    private static void scheduleRockShare(RockDonorPlan plan, Character recipient, int rockId, long initialDelayMs) {
        BotEntry donorEntry = plan.entry();
        Character donorBot = donorEntry.bot;
        int maxQty = plan.donationQty();
        BotManager.after(initialDelayMs, () -> {
            if (donorBot.getTrade() != null || donorEntry.pendingTradeCategory != null || recipient.getTrade() != null) {
                return;
            }
            List<Item> items = BotInventoryManager.collectRockShareItems(donorBot, rockId, maxQty);
            if (items.isEmpty()) {
                return;
            }
            BotManager.getInstance().botSay(donorBot, BotManager.randomReply(ROCK_OFFER_MSGS));
            BotManager.after(BotManager.randMs(900, 1100), () ->
                    BotInventoryManager.startRockShareTransfer(items, recipient, donorEntry, donorBot, maxQty));
        });
    }

    private static boolean isBetterDonor(RockDonorPlan candidate, RockDonorPlan best) {
        if (best == null) {
            return true;
        }
        if (candidate.donorNeedsSameRock() != best.donorNeedsSameRock()) {
            return !candidate.donorNeedsSameRock();        // prefer a non-user donor
        }
        return candidate.rockCount() > best.rockCount();   // else the bot with the most rocks
    }

    private static void saySupplyRequest(Character bot, String message) {
        if (bot.getParty() != null) {
            BotManager.getInstance().botSayParty(bot, message);
        } else {
            BotManager.getInstance().botSay(bot, message);
        }
    }

    record RockDonorPlan(BotEntry entry, int rockCount, boolean donorNeedsSameRock, int donationQty) {}
}
