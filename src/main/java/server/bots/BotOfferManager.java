package server.bots;

import client.BotClient;
import client.Character;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.WeaponType;
import config.YamlConfig;
import constants.inventory.ItemConstants;
import server.ItemInformationProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

final class BotOfferManager {
    private static final Pattern POSITIVE_CONFIRM_PATTERN = Pattern.compile(
            "\\b(yes|yep|yeah|yea|y|ok|sure|confirm|do\\s+it|go\\s+(ahead|for\\s+it))\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NEGATIVE_CONFIRM_PATTERN = Pattern.compile(
            "\\b(no|nope|nah|nvm|never\\s*mind|dont|don't|not\\s+now|skip)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final List<String> BOT_ACCEPT_MSGS = List.of(
            "sure!", "ok!", "yes", "y!", "y", "yes!", "yes pls", "yes please!", "yes please", "ooh nice, ty", "that would be great!", "that would be awesome!", "of course!");

    private enum GearOfferNeed {
        CURRENT,
        FUTURE
    }

    private record GearOfferChoice(Item item, GearOfferNeed need) {}

    private BotOfferManager() {}

    static boolean hasOfferReservation(BotEntry entry) {
        return entry.pendingLootOfferItem != null
                && entry.pendingLootOfferRecipientId > 0;
    }

    static boolean hasPendingOffer(BotEntry entry) {
        return hasOfferReservation(entry) && entry.pendingLootOfferExpiresAt > 0L;
    }

    static void notifyOwnerGainedEquip(BotEntry entry, Character bot, Item item) {
        if (BotChatManager.isOwnerIdle(entry)) {
            return;
        }
        if (entry.requestedUpgradeItemIds.contains(item.getItemId())) {
            return;
        }
        if (entry.pendingAction != null || entry.pendingTradeCategory != null || hasOfferReservation(entry)) {
            return;
        }
        Character owner = entry.owner;
        if (owner == null) {
            return;
        }

        if (BotEquipManager.findRecommendationForItem(bot, owner, item) == null) {
            return;
        }

        entry.requestedUpgradeItemIds.add(item.getItemId());
        createOwnerUpgradeRequest(entry, bot, owner, item);
    }

    static void requestBestUpgradeFromOwner(BotEntry entry, Character bot) {
        Character owner = entry.owner;
        if (owner == null) {
            return;
        }
        if (entry.pendingAction != null || entry.pendingTradeCategory != null || hasOfferReservation(entry)) {
            BotManager.getInstance().botReply(entry, "busy rn, ask me again in a bit");
            return;
        }
        List<BotEquipManager.EquipRecommendation> recs = BotEquipManager.findRecommendedEquips(bot, owner);
        if (recs.isEmpty()) {
            BotManager.getInstance().botReply(entry, "nothing i need from you rn, im good!");
            return;
        }
        Item candidate = recs.get(0).candidate();
        entry.requestedUpgradeItemIds.add(candidate.getItemId());
        createOwnerUpgradeRequest(entry, bot, owner, candidate);
    }

    static boolean offerBestRecommendedGear(BotEntry entry, Character bot, Character owner) {
        if (owner == null) {
            return false;
        }

        // Self-equip first so any item that would upgrade the bot stays on the bot
        // rather than being offered to the owner.
        BotEquipManager.autoEquip(bot, owner, entry.pendingLootOfferItem);

        GearOfferChoice choice = findBestGearOffer(entry, owner, bot);
        if (choice != null) {
            return offerGearItem(entry, bot, owner, choice.item(), choice.need());
        }

        Item throwingStar = findBestThrowingStarOffer(owner, bot);
        return throwingStar != null && offerGearItem(entry, bot, owner, throwingStar, GearOfferNeed.CURRENT);
    }

    static boolean offerBestGearToSibling(BotEntry entry, Character bot) {
        Character owner = entry.owner;
        if (owner == null) {
            return false;
        }

        // Self-equip first: priority is self → owner → sibling, so don't hand gear
        // to a sibling if this bot could actually wear it.
        BotEquipManager.autoEquip(bot, owner, entry.pendingLootOfferItem);

        List<BotEntry> siblings = BotManager.getInstance().getBotEntries(owner.getId());
        for (BotEntry sibling : siblings) {
            if (sibling == entry || sibling.bot == null || sibling.bot.getMapId() != bot.getMapId()) {
                continue;
            }
            GearOfferChoice choice = findBestGearOffer(entry, sibling.bot, bot);
            if (choice != null) {
                return offerGearItem(entry, bot, sibling.bot, choice.item(), choice.need());
            }
        }

        Character starRecipient = findWeakestThrowingStarRecipient(owner, bot);
        if (starRecipient == null) {
            return false;
        }
        Item throwingStar = findBestThrowingStarOffer(starRecipient, bot);
        return throwingStar != null
                && offerGearItem(entry, bot, starRecipient, throwingStar, GearOfferNeed.CURRENT);
    }

    /**
     * Proactively offer an equip scroll that is useless to THIS bot but useful to a cohort member /
     * owner who can actually use it (e.g. a bow-attack scroll -> archer, an INT scroll -> mage).
     * Category-aware (not just stat): the scroll must apply to gear the recipient wears AND grant a
     * stat their job values, while applying to nothing this bot wears. No class hardcoding — the
     * scroll-applicability ({@link BotScrollManager#applicable}) and job stat-relevance
     * ({@link BotEquipManager#relevantStatsFor}) SSOTs decide. Reuses the loot-offer flow (prompt +
     * auto-accept + trade); returns true once an offer is queued. Meta scrolls (clean slate / chaos /
     * modifier / white) are universally valuable and never offered away.
     */
    static boolean offerUselessScrollToCohort(BotEntry entry, Character bot) {
        Character owner = entry.owner;
        if (owner == null || owner == bot || bot.getTrade() != null
                || entry.pendingAction != null || entry.pendingTradeCategory != null
                || hasOfferReservation(entry)) {
            return false;
        }
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        for (Item s : bot.getInventory(InventoryType.USE).list()) {
            int sid = s.getItemId();
            if (!isOfferableScroll(ii, sid) || scrollUsefulTo(ii, bot, sid)) {
                continue; // not a category-specific scroll, or this bot can still use it -> keep
            }
            Character recipient = bestScrollRecipient(entry, bot, owner, ii, sid);
            if (recipient != null) {
                return offerGearItem(entry, bot, recipient, s, GearOfferNeed.CURRENT);
            }
        }
        return false;
    }

    /** An equip scroll worth routing by category (skips universally-valuable meta scrolls). */
    private static boolean isOfferableScroll(ItemInformationProvider ii, int sid) {
        if (!ItemConstants.isEquipScroll(sid)) {
            return false;
        }
        if (ItemConstants.isCleanSlate(sid) || ItemConstants.isChaosScroll(sid)
                || ItemConstants.isModifierScroll(sid) || sid == constants.id.ItemId.WHITE_SCROLL) {
            return false;
        }
        return ii.getEquipStats(sid) != null;
    }

    /** Useful to {@code c} = the scroll grants a stat c's job values AND applies to gear c wears. */
    private static boolean scrollUsefulTo(ItemInformationProvider ii, Character c, int sid) {
        if (!scrollStatRelevantTo(ii, c, sid)) {
            return false;
        }
        for (Item it : c.getInventory(InventoryType.EQUIPPED).list()) {
            if (it instanceof Equip e && !ii.isCash(e.getItemId())
                    && BotScrollManager.applicable(ii, sid, e.getItemId())) {
                return true;
            }
        }
        return false;
    }

    private static boolean scrollStatRelevantTo(ItemInformationProvider ii, Character c, int sid) {
        return scrollStatRelevantToJob(ii.getEquipStats(sid), c.getJob());
    }

    /** Pure seam: does a scroll's stat block grant anything {@code job} values? (WZ-free, testable.) */
    static boolean scrollStatRelevantToJob(java.util.Map<String, Integer> scrollStats, client.Job job) {
        if (scrollStats == null || job == null) {
            return false;
        }
        for (BotEquipManager.RelevantStat stat : BotEquipManager.relevantStatsFor(job)) {
            if (scrollStats.getOrDefault(BotInventoryManager.scrollStatKey(stat), 0) > 0) {
                return true;
            }
        }
        return false;
    }

    /** Best same-map recipient (owner + cohort siblings) for whom the scroll is useful — the one
     *  with the most applicable worn gear that still has free upgrade slots (most immediately usable). */
    private static Character bestScrollRecipient(BotEntry entry, Character bot, Character owner,
            ItemInformationProvider ii, int sid) {
        Character best = null;
        int bestScore = -1;
        List<Character> candidates = new ArrayList<>();
        if (owner != bot && owner.getMapId() == bot.getMapId()) {
            candidates.add(owner);
        }
        for (BotEntry sib : BotManager.getInstance().getBotEntries(owner.getId())) {
            if (sib == entry || sib.bot == null || sib.bot == bot || sib.bot.getMapId() != bot.getMapId()) {
                continue;
            }
            candidates.add(sib.bot);
        }
        for (Character c : candidates) {
            if (!scrollUsefulTo(ii, c, sid)) {
                continue;
            }
            int score = scrollUsableSlotScore(ii, c, sid);
            if (score > bestScore) {
                bestScore = score;
                best = c;
            }
        }
        return best;
    }

    /** How many worn equips the scroll applies to still have a free upgrade slot to spend it on. */
    private static int scrollUsableSlotScore(ItemInformationProvider ii, Character c, int sid) {
        int score = 0;
        for (Item it : c.getInventory(InventoryType.EQUIPPED).list()) {
            if (it instanceof Equip e && !ii.isCash(e.getItemId())
                    && BotScrollManager.applicable(ii, sid, e.getItemId()) && e.getUpgradeSlots() > 0) {
                score++;
            }
        }
        return score;
    }

    static void scheduleLootOfferPrompt(BotEntry entry, Character bot, Item item, long delayMs) {
        Character owner = entry.owner;
        long now = System.currentTimeMillis();
        if (owner == null
                || item == null
                || entry.pendingGearPromptAt > now
                || BotChatManager.isOwnerIdle(entry)
                || entry.pendingAction != null
                || entry.pendingTradeCategory != null
                || hasOfferReservation(entry)
                || !BotInventoryManager.hasItem(bot, item)) {
            return;
        }

        Character recipient = findLootOfferRecipient(entry, bot, item);
        if (recipient == null) {
            return;
        }

        entry.pendingDropCategory = null;
        entry.pendingLootOfferItem = item;
        entry.pendingLootOfferRecipientId = recipient.getId();
        entry.pendingLootOfferExpiresAt = 0L;
        entry.pendingLootOfferBotRequesting = false;

        long scheduledAt = now + Math.max(0L, delayMs);
        entry.pendingGearPromptAt = scheduledAt;
        BotManager.after(delayMs, () -> promptLootOfferAfterLoot(entry, bot, item, recipient.getId(), scheduledAt));
    }

    static boolean handlePendingOfferResponse(BotEntry entry, Character speaker, String message) {
        expirePendingOffer(entry);
        if (!hasPendingOffer(entry)
                || speaker == null
                || speaker.getId() != entry.pendingLootOfferRecipientId) {
            return false;
        }

        if (POSITIVE_CONFIRM_PATTERN.matcher(message).find()) {
            if (entry.pendingLootOfferBotRequesting) {
                clearPendingOffer(entry);
                BotManager.after(BotManager.randMs(400, 600), () ->
                        BotManager.getInstance().botReply(entry, "ty! inv me?"));
            } else {
                Item item = entry.pendingLootOfferItem;
                entry.pendingDropCategory = null;
                entry.pendingLootOfferExpiresAt = 0L;
                entry.pendingLootOfferBotRequesting = false;
                entry.pendingLootOfferRecipientId = 0;
                BotManager.after(BotManager.randMs(900, 1100), () -> {
                    entry.pendingLootOfferItem = null;
                    BotInventoryManager.startTradeTransfer(item, speaker, entry, entry.bot);
                });
            }
            return true;
        }
        if (NEGATIVE_CONFIRM_PATTERN.matcher(message).find()) {
            clearPendingOffer(entry);
            BotManager.after(BotManager.randMs(400, 600), () -> {
                if (entry.owner != null && speaker.getId() == entry.owner.getId()) {
                    BotManager.getInstance().botReply(entry, "ok, keeping it for now");
                } else {
                    BotManager.getInstance().botSay(entry.bot, "ok, keeping it for now");
                }
            });
            return true;
        }

        return false;
    }

    static void expirePendingOffer(BotEntry entry) {
        if (hasPendingOffer(entry) && System.currentTimeMillis() >= entry.pendingLootOfferExpiresAt) {
            clearPendingOffer(entry);
        }
    }

    static void clearPendingOfferForOwnerAsk(BotEntry entry) {
        clearPendingOffer(entry);
    }

    private static void createOwnerUpgradeRequest(BotEntry entry, Character bot, Character owner, Item ownerItem) {
        // Audience for the specifier is the bot itself: it's describing why the item
        // is good for it, so format stats relative to the bot's job.
        String itemDesc = formatItemSpecifier(ownerItem, bot);

        entry.pendingDropCategory = null;
        entry.pendingLootOfferItem = ownerItem;
        entry.pendingLootOfferRecipientId = owner.getId();
        entry.pendingLootOfferExpiresAt = System.currentTimeMillis() + 45_000L;
        entry.pendingLootOfferBotRequesting = true;

        List<String> prompts = List.of(
                "hey, that " + itemDesc + " would be an upgrade for me, can i have it pls?",
                "Can I have your " + itemDesc + "?",
                "Your " + itemDesc + " would be better on me! trade it over?",
                "I could use that " + itemDesc + " of yours ;)",
                "that " + itemDesc + " is an upgrade for me, want to trade?");
        String prompt = BotManager.randomReply(prompts);
        BotChatManager.queueBotSay(entry, prompt, List.of("yes", "no"));
    }

    private static boolean offerGearItem(BotEntry entry, Character bot, Character recipient, Item item,
                                         GearOfferNeed need) {
        if (entry.pendingAction != null || entry.pendingTradeCategory != null || hasOfferReservation(entry)
                || !BotInventoryManager.hasItem(bot, item)) {
            return false;
        }
        entry.pendingDropCategory = null;
        entry.pendingLootOfferItem = item;
        entry.pendingLootOfferRecipientId = recipient.getId();
        entry.pendingLootOfferExpiresAt = System.currentTimeMillis() + 30_000L;
        entry.pendingLootOfferBotRequesting = false;
        long promptDelayMs = BotChatManager.queueBotSayWithEstimatedDelay(entry,
                buildLootOfferPrompt(recipient, entry.owner, item, need == GearOfferNeed.FUTURE));
        scheduleBotLootOfferAutoAccept(entry, recipient, promptDelayMs);
        return true;
    }

    private static void promptLootOfferAfterLoot(BotEntry entry, Character bot, Item item, int recipientId, long scheduledAt) {
        if (entry.pendingGearPromptAt != scheduledAt) {
            return;
        }
        entry.pendingGearPromptAt = 0L;

        if (entry.pendingLootOfferItem != item || entry.pendingLootOfferRecipientId != recipientId) {
            clearPendingOffer(entry);
            return;
        }

        Character owner = entry.owner;
        Character recipient = resolveReservedOfferRecipient(entry, bot, recipientId);
        if (owner == null
                || entry.pendingAction != null
                || entry.pendingTradeCategory != null
                || recipient == null
                || !BotInventoryManager.hasItem(bot, item)) {
            clearPendingOffer(entry);
            return;
        }

        if (ItemConstants.getInventoryType(item.getItemId()) == InventoryType.EQUIP
                && BotEquipManager.shouldReserveOwnedItem(bot, item)) {
            clearPendingOffer(entry);
            return;
        }
        GearOfferNeed need = gearOfferNeed(entry, recipient, bot, item);
        if (ItemConstants.getInventoryType(item.getItemId()) == InventoryType.EQUIP && need == null) {
            clearPendingOffer(entry);
            return;
        }
        entry.pendingDropCategory = null;
        entry.pendingLootOfferItem = item;
        entry.pendingLootOfferRecipientId = recipient.getId();
        entry.pendingLootOfferExpiresAt = System.currentTimeMillis() + 30_000L;
        entry.pendingLootOfferBotRequesting = false;
        String offerPrompt = buildLootOfferPrompt(recipient, owner, item, need == GearOfferNeed.FUTURE);
        // The loot can be offered to a sibling bot; the hint overlay is owner-facing, so only attach it
        // when the owner is the one being asked.
        List<String> offerOptions = recipient.getId() == owner.getId() ? List.of("yes", "no") : null;
        long promptDelayMs = BotChatManager.queueBotSayWithEstimatedDelay(entry, offerPrompt, offerOptions);
        scheduleBotLootOfferAutoAccept(entry, recipient, promptDelayMs);
    }

    private static void scheduleBotLootOfferAutoAccept(BotEntry entry, Character recipient, long promptDelayMs) {
        if (!(recipient.getClient() instanceof BotClient)) {
            return;
        }
        long replyDelayMs = promptDelayMs + BotManager.randMs(1800, 2200);
        BotManager.after(replyDelayMs, () -> autoAcceptLootOffer(entry, recipient));
    }

    private static void autoAcceptLootOffer(BotEntry entry, Character recipientBot) {
        if (!hasPendingOffer(entry) || entry.pendingLootOfferRecipientId != recipientBot.getId()) {
            return;
        }
        BotManager.getInstance().botSay(recipientBot, entry.replyChannel, BotManager.randomReply(BOT_ACCEPT_MSGS));
        handlePendingOfferResponse(entry, recipientBot, "yes");
    }

    static String buildLootOfferPrompt(String recipientName, String itemName, boolean targetIsOwner) {
        return buildSharedLootOfferPrompt(recipientName, itemName, false);
    }

    static String buildLootOfferPrompt(String recipientName, String itemName, boolean targetIsOwner, boolean forLater) {
        return buildSharedLootOfferPrompt(recipientName, itemName, forLater);
    }

    private static String buildSharedLootOfferPrompt(String recipientName, String itemName, boolean forLater) {
        List<String> prompts = forLater
                ? List.of(
                        "%s, you might need %s later, want it?",
                        "%s, picked up %s, could help later if you want it",
                        "%s, I got %s for later if you want it",
                        "%s, %s looks useful for later, want me to trade it over?",
                        "%s, holding %s in case you want it later",
                        "%s, saved %s for later if you want it")
                : List.of(
                        "%s, I have %s, you want?",
                        "%s, picked up %s, want it?",
                        "%s, I got %s if you want it",
                        "%s, want %s?",
                        "%s, I can trade you %s",
                        "%s, grabbed %s for you if you want it");
        String format = BotManager.randomReply(prompts);
        return String.format(format, recipientName, itemName);
    }

    private static String buildLootOfferPrompt(Character recipient, Character owner, Item item, boolean forLater) {
        String itemDesc = formatItemSpecifier(item, recipient);
        return buildSharedLootOfferPrompt(recipient.getName(), itemDesc, forLater);
    }

    /**
     * Returns "<spec> <itemName>" with up to 2 stat tokens in priority order:
     * 1) att (or matt for mage audience), 2) main stat, 3) secondary stat.
     * Tokens with value 0 are skipped — att/matt is NOT gated by slot type, since
     * gloves/capes/earrings can also carry att or matt. Audience's job decides
     * which stats are "main"/"secondary" and whether matt outranks att.
     * Weapon att is rendered without a leading "+" ("30 att maple bow"); all
     * other tokens use "+" since they are bonus values ("+3 str", "+3 att").
     */
    static String formatItemSpecifier(Item item, Character audience) {
        if (item instanceof Equip && audience == null) {
            String name = ItemInformationProvider.getInstance().getName(item.getItemId());
            return name == null || name.isBlank() ? String.valueOf(item.getItemId()) : name;
        }
        int jobId = audience == null || audience.getJob() == null ? 0 : audience.getJob().getId();
        return formatItemSpecifier(item, jobId);
    }

    /** Same specifier with the perspective job given directly — @autosell keys it on the
     *  ITEM's class (reqJob) since the seller is mostly unloading other jobs' gear. */
    static String formatItemSpecifier(Item item, int jobId) {
        String name = ItemInformationProvider.getInstance().getName(item.getItemId());
        if (name == null || name.isBlank()) {
            name = String.valueOf(item.getItemId());
        }
        if (!(item instanceof Equip eq)) {
            return name;
        }

        boolean mageBranch = isMageBranch(jobId);
        boolean weapon = ItemConstants.isWeapon(item.getItemId());
        char[] order = mainSecondaryStats(jobId);

        int attVal = mageBranch ? eq.getMatk() : eq.getWatk();
        String attLabel = mageBranch ? "matt" : "att";
        int mainVal = statValue(eq, order[0]);
        int secVal = statValue(eq, order[1]);

        List<String> tokens = new ArrayList<>(2);
        if (attVal > 0) {
            tokens.add(weapon ? (attVal + " " + attLabel) : ("+" + attVal + " " + attLabel));
        }
        if (tokens.size() < 2 && mainVal > 0) {
            tokens.add("+" + mainVal + " " + statName(order[0]));
        }
        if (tokens.size() < 2 && secVal > 0) {
            tokens.add("+" + secVal + " " + statName(order[1]));
        }

        if (tokens.isEmpty()) {
            return name;
        }
        return String.join(" ", tokens) + " " + name;
    }

    private static boolean isMageBranch(int jobId) {
        return (jobId >= 200 && jobId < 300)
                || (jobId >= 1200 && jobId < 1300)
                || jobId == 2001
                || (jobId >= 2200 && jobId < 2300);
    }

    // Returns 2-char [main, secondary] stat codes: s=str, d=dex, i=int, l=luk
    private static char[] mainSecondaryStats(int jobId) {
        // Magician branches: INT main, LUK secondary
        if (isMageBranch(jobId)) return new char[]{'i', 'l'};
        // Bowman / Wind Archer: DEX main, STR secondary
        if ((jobId >= 300 && jobId < 400) || (jobId >= 1300 && jobId < 1400)) return new char[]{'d', 's'};
        // Thief / Night Walker: LUK main, DEX secondary
        if ((jobId >= 400 && jobId < 500) || (jobId >= 1400 && jobId < 1500)) return new char[]{'l', 'd'};
        // Pirate gunslinger sub-branch: DEX main, STR secondary
        if (jobId >= 520 && jobId < 530) return new char[]{'d', 's'};
        // Pirate brawler sub-branch + Thunderbreaker: STR main, DEX secondary
        if ((jobId >= 510 && jobId < 520) || (jobId >= 1500 && jobId < 1600)) return new char[]{'s', 'd'};
        // Warrior / Dawn Warrior / Aran / Pirate-beginner / fallback: STR main, DEX secondary
        return new char[]{'s', 'd'};
    }

    private static int statValue(Equip eq, char code) {
        return switch (code) {
            case 's' -> eq.getStr();
            case 'd' -> eq.getDex();
            case 'i' -> eq.getInt();
            case 'l' -> eq.getLuk();
            default -> 0;
        };
    }

    private static String statName(char code) {
        return switch (code) {
            case 's' -> "str";
            case 'd' -> "dex";
            case 'i' -> "int";
            case 'l' -> "luk";
            default -> "";
        };
    }

    private static Character findLootOfferRecipient(BotEntry entry, Character bot, Item item) {
        Character owner = entry.owner;
        if (owner == null) {
            return null;
        }
        if (ItemConstants.isThrowingStar(item.getItemId())) {
            if (isBetterThrowingStarForRecipient(owner, bot, item)) {
                return owner;
            }
            return findWeakestThrowingStarRecipient(owner, bot, item);
        }

        if (BotEquipManager.shouldReserveOwnedItem(bot, item)) {
            return null;
        }

        if (gearOfferNeed(entry, owner, bot, item) != null) {
            return owner;
        }
        for (Character member : eligibleBotRecipients(owner, bot)) {
            if (gearOfferNeed(entry, member, bot, item) != null) {
                return member;
            }
        }
        return null;
    }

    private static GearOfferChoice findBestGearOffer(BotEntry entry, Character recipient, Character donor) {
        List<Equip> offerable = collectOfferableEquips(donor);
        offerable.removeIf(equip -> !isWeaponOfferCompatible(recipient, equip));
        List<BotEquipManager.EquipRecommendation> current =
                BotEquipManager.findRecommendedEquipsFromItems(recipient, offerable);
        if (!current.isEmpty()) {
            return new GearOfferChoice(current.get(0).candidate(), GearOfferNeed.CURRENT);
        }
        if (entry != null && entry.proactiveUpgradeOffers) {
            ItemInformationProvider ii = ItemInformationProvider.getInstance();
            for (Equip equip : offerable) {
                if (BotEquipManager.wouldReserveIncomingItem(recipient, ii, equip)) {
                    return new GearOfferChoice(equip, GearOfferNeed.FUTURE);
                }
            }
        }
        return null;
    }

    private static List<Equip> collectOfferableEquips(Character donor) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        Inventory equipInv = donor.getInventory(InventoryType.EQUIP);
        List<Equip> offerable = new ArrayList<>();
        for (Item item : equipInv.list()) {
            if (!(item instanceof Equip equip) || ii.isCash(item.getItemId())) {
                continue;
            }
            if (item.isUntradeable() && !YamlConfig.config.server.UNTRADEABLE_ITEMS_TRADEABLE) {
                continue;
            }
            if (BotEquipManager.shouldReserveOwnedItem(donor, item)) {
                continue;
            }
            offerable.add(equip);
        }
        return offerable;
    }
    private static GearOfferNeed gearOfferNeed(BotEntry entry, Character recipient, Character donor, Item item) {
        if (!isWeaponOfferCompatible(recipient, item)) {
            return null;
        }
        if (BotEquipManager.findRecommendationForItem(recipient, donor, item) != null) {
            return GearOfferNeed.CURRENT;
        }
        if (entry != null && entry.proactiveUpgradeOffers && item instanceof Equip equip) {
            if (BotEquipManager.wouldReserveIncomingItem(recipient, ItemInformationProvider.getInstance(), equip)) {
                return GearOfferNeed.FUTURE;
            }
        }
        return null;
    }

    static boolean isWeaponOfferCompatible(Character recipient, Item item) {
        if (!(item instanceof Equip equip)) {
            return true;
        }
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        if (!ItemConstants.isWeapon(equip.getItemId())) {
            return true;
        }
        return isWeaponOfferCompatible(recipient, ii.getWeaponType(equip.getItemId()), equip);
    }

    static boolean isWeaponOfferCompatible(Character recipient, WeaponType weaponType) {
        return BotEquipManager.isWeaponCompatible(recipient, weaponType);
    }

    /** Equip-aware variant: lets a mage recipient claim off-type MATK weapons (same SSOT as
     *  the equip/reserve pipeline — see BotEquipManager.isWeaponCompatible(bot, type, equip)). */
    static boolean isWeaponOfferCompatible(Character recipient, WeaponType weaponType, Equip equip) {
        return BotEquipManager.isWeaponCompatible(recipient, weaponType, equip);
    }

    static boolean isReservedForOtherRecipients(BotEntry entry, Character donor, Item item) {
        if (entry == null || donor == null || item == null) {
            return false;
        }

        Character owner = entry.owner;
        if (owner == null) {
            return false;
        }

        if (ItemConstants.isThrowingStar(item.getItemId())) {
            if (isBetterThrowingStarForRecipient(owner, donor, item)) {
                return true;
            }
            for (Character member : eligibleBotRecipients(owner, donor)) {
                if (isBetterThrowingStarForRecipient(member, donor, item)) {
                    return true;
                }
            }
            return false;
        }

        if (ItemConstants.getInventoryType(item.getItemId()) != InventoryType.EQUIP) {
            return false;
        }
        if (!(item instanceof Equip equip)) {
            return false;
        }
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        // Trade-classification path: only the FUTURE (Pareto self-reserve) check is used here.
        // The IMMEDIATE optimizer-DP check that gearOfferNeed() also runs is intentionally
        // skipped — it's expensive and its picks are essentially a subset of the FUTURE set,
        // so it adds no signal for "should this item be held back from a player→bot trade?".
        // Proactive offer paths still call gearOfferNeed() directly and keep both checks.
        if (isFutureReservedForRecipient(owner, equip, ii)) {
            return true;
        }
        for (Character member : eligibleBotRecipients(owner, donor)) {
            if (isFutureReservedForRecipient(member, equip, ii)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isFutureReservedForRecipient(Character recipient, Equip equip, ItemInformationProvider ii) {
        if (!isWeaponOfferCompatible(recipient, equip)) {
            return false;
        }
        return BotEquipManager.wouldReserveIncomingItem(recipient, ii, equip);
    }

    private static Character findWeakestThrowingStarRecipient(Character owner, Character donor) {
        Character bestRecipient = null;
        int bestCurrentWatk = Integer.MAX_VALUE;
        for (Character member : eligibleBotRecipients(owner, donor)) {
            Item candidate = findBestThrowingStarOffer(member, donor);
            if (candidate == null) {
                continue;
            }
            int currentWatk = bestThrowingStarAttack(member);
            if (currentWatk < bestCurrentWatk) {
                bestRecipient = member;
                bestCurrentWatk = currentWatk;
            }
        }
        return bestRecipient;
    }

    private static Character findWeakestThrowingStarRecipient(Character owner, Character donor, Item item) {
        Character bestRecipient = null;
        int bestCurrentWatk = Integer.MAX_VALUE;
        for (Character member : eligibleBotRecipients(owner, donor)) {
            if (!isBetterThrowingStarForRecipient(member, donor, item)) {
                continue;
            }
            int currentWatk = bestThrowingStarAttack(member);
            if (currentWatk < bestCurrentWatk) {
                bestRecipient = member;
                bestCurrentWatk = currentWatk;
            }
        }
        return bestRecipient;
    }

    private static List<Character> eligibleBotRecipients(Character owner, Character donor) {
        BotOwnershipService ownership = BotOwnershipService.getInstance();
        return owner.getPartyMembersOnSameMap().stream()
                .filter(member -> member != null)
                .filter(member -> member.getId() != owner.getId())
                .filter(member -> member.getId() != donor.getId())
                .filter(member -> member.getClient() instanceof BotClient)
                .filter(member -> ownership.isAuthorizedOwner(member.getId(), owner.getId()))
                .toList();
    }

    private static Item findBestThrowingStarOffer(Character recipient, Character donor) {
        Inventory useInv = donor.getInventory(InventoryType.USE);
        Item best = null;
        int bestWatk = 0;
        for (Item item : useInv.list()) {
            if (!isBetterThrowingStarForRecipient(recipient, donor, item)) {
                continue;
            }
            int watk = throwingStarAttack(item);
            if (watk > bestWatk) {
                best = item;
                bestWatk = watk;
            }
        }
        return best;
    }

    static boolean isBetterThrowingStarForRecipient(Character recipient, Character donor, Item candidate) {
        if (candidate == null || !ItemConstants.isThrowingStar(candidate.getItemId())) {
            return false;
        }
        if (BotAttackExecutionProvider.getEquippedWeaponType(recipient) != WeaponType.CLAW) {
            return false;
        }
        int candidateWatk = throwingStarAttack(candidate);
        if (candidateWatk < bestThrowingStarAttack(recipient)) {
            return false;
        }
        return BotAttackExecutionProvider.getEquippedWeaponType(donor) != WeaponType.CLAW
                || candidateWatk < bestThrowingStarAttack(donor);
    }

    private static int bestThrowingStarAttack(Character character) {
        Inventory useInv = character.getInventory(InventoryType.USE);
        int best = 0;
        for (Item item : useInv.list()) {
            if (ItemConstants.isThrowingStar(item.getItemId())) {
                best = Math.max(best, throwingStarAttack(item));
            }
        }
        return best;
    }

    private static int throwingStarAttack(Item item) {
        return ItemInformationProvider.getInstance().getWatkForProjectile(item.getItemId());
    }

    private static Character resolveReservedOfferRecipient(BotEntry entry, Character bot, int recipientId) {
        Character owner = entry.owner;
        if (owner != null && owner.getId() == recipientId) {
            return owner;
        }
        if (bot.getMap() != null) {
            Character onMap = bot.getMap().getCharacterById(recipientId);
            if (onMap != null) {
                return onMap;
            }
        }
        if (owner != null) {
            for (Character member : owner.getPartyMembersOnSameMap()) {
                if (member != null && member.getId() == recipientId) {
                    return member;
                }
            }
        }
        return null;
    }

    private static void clearPendingOffer(BotEntry entry) {
        entry.pendingDropCategory = null;
        entry.pendingLootOfferItem = null;
        entry.pendingLootOfferRecipientId = 0;
        entry.pendingLootOfferExpiresAt = 0L;
        entry.pendingLootOfferBotRequesting = false;
        entry.pendingGearPromptAt = 0L;
    }
}
