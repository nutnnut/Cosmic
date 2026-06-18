package server.bots;

import client.Character;
import client.inventory.Item;
import client.inventory.WeaponType;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class BotAmmoManager {
    private static final Map<String, Long> ammoShareBackoffUntil = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> ammoShareCooldownUntil = new ConcurrentHashMap<>();

    private static final List<String> ARROW_REQUEST_MSGS = List.of(
            "low on arrows, anyone have spare?",
            "need arrows soon, anyone got extras?",
            "running low on arrows, can someone share?");
    private static final List<String> BOLT_REQUEST_MSGS = List.of(
            "low on bolts, anyone have spare?",
            "need crossbow bolts soon, anyone got extras?",
            "running low on bolts, can someone share?");
    private static final List<String> STAR_REQUEST_MSGS = List.of(
            "low on stars, anyone have spare?",
            "need throwing stars soon, anyone got extras?",
            "running low on throwing stars, can someone share?");
    private static final List<String> BULLET_REQUEST_MSGS = List.of(
            "low on bullets, anyone have spare?",
            "need bullets soon, anyone got extras?",
            "running low on bullets, can someone share?");
    private static final List<String> AMMO_OFFER_MSGS = List.of(
            "i have spare ammo, inv u",
            "got some ammo for you, trading",
            "i can spare ammo, one sec");

    private BotAmmoManager() {}

    static void tickAmmoShareCheck(BotEntry entry, Character bot) {
        requestLowAmmoShare(entry, bot, false);
    }

    static boolean requestLowAmmoShare(BotEntry entry, Character bot, boolean bypassShareLimits) {
        WeaponType weaponType = BotAttackExecutionProvider.getEquippedWeaponType(bot);
        if (!canRequestShare(weaponType)) {
            entry.ammoShareRequested = false;
            return false;
        }

        int ammo = BotCombatManager.countAmmo(bot, weaponType);
        if (ammo >= BotCombatManager.cfg.AMMO_LOW_WARN) {
            entry.ammoShareRequested = false;
            return false;
        }

        if ((!entry.ammoShareRequested || bypassShareLimits)
                && requestAmmoShare(entry, bot, weaponType, ammo, bypassShareLimits)) {
            entry.ammoShareRequested = true;
            return true;
        }
        return false;
    }

    static void checkAmmoShareOnModeStart(BotEntry entry, Character bot) {
        entry.ammoShareRequested = false;
        requestLowAmmoShare(entry, bot, false);
    }

    static boolean requestAmmoShare(BotEntry entry, Character bot, WeaponType weaponType, int currentAmmo) {
        return requestAmmoShare(entry, bot, weaponType, currentAmmo, false);
    }

    static boolean requestAmmoShare(BotEntry entry, Character bot, WeaponType weaponType, int currentAmmo, boolean bypassShareLimits) {
        Character owner = entry.owner;
        if (owner == null || bot.getTrade() != null || entry.pendingTradeCategory != null) {
            return false;
        }
        if (!canRequestShare(weaponType) || currentAmmo >= BotCombatManager.cfg.AMMO_LOW_WARN) {
            return false;
        }

        long now = System.currentTimeMillis();
        String backoffKey = owner.getId() + ":" + weaponType.name();
        if (!bypassShareLimits) {
            if (now < ammoShareBackoffUntil.getOrDefault(backoffKey, 0L)) {
                return false;
            }
            if (now < ammoShareCooldownUntil.getOrDefault(owner.getId(), 0L)) {
                return false;
            }
            ammoShareCooldownUntil.put(owner.getId(), now + 30_000L);
        }

        BotAutopilotManager.noteLowSupplyPartyRequest(entry);
        saySupplyRequest(bot, BotManager.randomReply(requestMessages(weaponType)));

        AmmoDonorPlan plan = selectAmmoDonor(entry, bot, weaponType);
        if (plan == null) {
            if (!bypassShareLimits) {
                ammoShareBackoffUntil.put(backoffKey, now + 10 * 60_000L);
            }
            return true;
        }

        scheduleAmmoShare(plan, bot, weaponType, BotManager.randMs(2000, 3000));
        return true;
    }

    enum OwnerAmmoShareResult {
        OFFERED,
        NO_DONOR,
        BLOCKED
    }

    static OwnerAmmoShareResult offerAmmoShareToOwner(BotEntry entry, WeaponType weaponType) {
        Character owner = entry.owner;
        if (owner == null || owner.getTrade() != null || !canRequestShare(weaponType)) {
            return OwnerAmmoShareResult.BLOCKED;
        }

        AmmoDonorPlan plan = selectAmmoDonorForRecipient(owner, weaponType);
        if (plan == null) {
            return OwnerAmmoShareResult.NO_DONOR;
        }

        scheduleAmmoShare(plan, owner, weaponType, BotManager.randMs(900, 1400));
        return OwnerAmmoShareResult.OFFERED;
    }

    static AmmoDonorPlan selectAmmoDonor(BotEntry needyEntry, Character needyBot, WeaponType needyWeaponType) {
        Character owner = needyEntry.owner;
        if (owner == null || !canRequestShare(needyWeaponType)) {
            return null;
        }
        return selectAmmoDonor(owner.getId(), needyBot.getMapId(), needyEntry, needyWeaponType);
    }

    static AmmoDonorPlan selectAmmoDonorForRecipient(Character recipient, WeaponType needyWeaponType) {
        if (recipient == null || !canRequestShare(needyWeaponType)) {
            return null;
        }
        return selectAmmoDonor(recipient.getId(), recipient.getMapId(), null, needyWeaponType);
    }

    private static AmmoDonorPlan selectAmmoDonor(int ownerId, int mapId, BotEntry excludedEntry, WeaponType needyWeaponType) {
        AmmoDonorPlan best = null;
        for (BotEntry sibling : BotManager.getInstance().shareCandidateEntries(ownerId, excludedEntry)) {
            if (sibling == excludedEntry || sibling.bot == null || sibling.bot.getMapId() != mapId) {
                continue;
            }
            Character donorBot = sibling.bot;
            int count = BotCombatManager.countAmmo(donorBot, needyWeaponType);
            if (count < BotCombatManager.cfg.AMMO_LOW_WARN) {
                continue;
            }
            WeaponType donorWeaponType = BotAttackExecutionProvider.getEquippedWeaponType(donorBot);
            boolean donorNeedsSameAmmo = donorWeaponType == needyWeaponType;
            int donationQty = donorNeedsSameAmmo ? (count - BotCombatManager.cfg.AMMO_LOW_WARN) / 2 : count;
            if (donationQty <= 0) {
                continue;
            }
            AmmoDonorPlan candidate = new AmmoDonorPlan(sibling, count, donorNeedsSameAmmo, donationQty);
            if (isBetterDonor(candidate, best)) {
                best = candidate;
            }
        }
        return best;
    }

    private static void scheduleAmmoShare(AmmoDonorPlan plan, Character recipient, WeaponType weaponType, long initialDelayMs) {
        BotEntry donorEntry = plan.entry();
        Character donorBot = donorEntry.bot;
        int maxQty = plan.donationQty();
        BotManager.after(initialDelayMs, () -> {
            if (donorBot.getTrade() != null || donorEntry.pendingTradeCategory != null || recipient.getTrade() != null) {
                return;
            }
            List<Item> items = BotInventoryManager.collectAmmoShareItems(donorBot, weaponType, maxQty);
            if (items.isEmpty()) {
                return;
            }
            BotManager.getInstance().botSay(donorBot, BotManager.randomReply(AMMO_OFFER_MSGS));
            BotManager.after(BotManager.randMs(900, 1100), () ->
                    BotInventoryManager.startAmmoShareTransfer(items, recipient, donorEntry, donorBot, maxQty));
        });
    }

    private static void saySupplyRequest(Character bot, String message) {
        if (bot.getParty() != null) {
            BotManager.getInstance().botSayParty(bot, message);
        } else {
            BotManager.getInstance().botSay(bot, message);
        }
    }

    private static boolean isBetterDonor(AmmoDonorPlan candidate, AmmoDonorPlan best) {
        if (best == null) {
            return true;
        }
        if (candidate.donorNeedsSameAmmo() != best.donorNeedsSameAmmo()) {
            return !candidate.donorNeedsSameAmmo();
        }
        return candidate.matchingAmmoCount() > best.matchingAmmoCount();
    }

    private static boolean canRequestShare(WeaponType weaponType) {
        return weaponType == WeaponType.BOW || weaponType == WeaponType.CROSSBOW
                || weaponType == WeaponType.CLAW || weaponType == WeaponType.GUN;
    }

    private static List<String> requestMessages(WeaponType weaponType) {
        return switch (weaponType) {
            case BOW -> ARROW_REQUEST_MSGS;
            case CROSSBOW -> BOLT_REQUEST_MSGS;
            case CLAW -> STAR_REQUEST_MSGS;
            case GUN -> BULLET_REQUEST_MSGS;
            default -> ARROW_REQUEST_MSGS;
        };
    }

    record AmmoDonorPlan(BotEntry entry, int matchingAmmoCount, boolean donorNeedsSameAmmo, int donationQty) {}
}
