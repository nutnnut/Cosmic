package server.bots;

import client.BotClient;
import client.Character;
import client.Client;
import client.Job;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.WeaponType;
import client.inventory.manipulator.InventoryManipulator;
import config.YamlConfig;
import constants.game.GameConstants;
import constants.id.ItemId;
import constants.inventory.ItemConstants;
import net.packet.Packet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.StatEffect;
import server.Trade;
import server.maps.FieldLimit;
import server.maps.MapItem;
import server.maps.MapleMap;
import tools.PacketCreator;

import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;
import java.util.function.Predicate;

class BotInventoryManager {
    private static final Logger log = LoggerFactory.getLogger(BotInventoryManager.class);
    private static final long TRADE_COMMAND_PROFILE_WARN_NS = 50_000_000L;
    private static final int MANUAL_TRADE_TIMEOUT_MS = 60_000;
    private static final int TRADE_WINDOW_ITEM_LIMIT = 9;
    private static final String RESERVED_EQUIPS_CATEGORY_PREFIX = "equips:reserved:";
    private static final Map<Integer, Optional<StatEffect>> itemEffectCache = new ConcurrentHashMap<>();
    private record PreparedTradeItems(List<Item> items, String errorMessage) {}
    record EquipTradeGroups(List<Item> normal,
                                    List<Item> reservedForOther,
                                    List<Item> reservedForSelf) {
        List<Item> itemsFor(EquipsGroup group) {
            return switch (group) {
                case NORMAL -> normal;
                case RESERVED_FOR_OTHER -> reservedForOther;
                case RESERVED_FOR_SELF -> reservedForSelf;
            };
        }
    }
    private record UseTradeGroups(List<Item> uncategorized, List<Item> categorized) {}
    private record AmmoTradeGroups(List<Item> nonOwn, List<Item> own) {
        List<Item> itemsFor(AmmoGroup group) {
            return switch (group) {
                case NON_OWN -> nonOwn;
                case OWN -> own;
            };
        }
    }

    private static final Set<Integer> manualTradeGreetingSent = ConcurrentHashMap.newKeySet();
    private static final Map<Integer, String> normalizedItemNameCache = new ConcurrentHashMap<>();
    private static final List<String> TRADE_INVITATION_MSGS = List.of(
            "k", "ok", "kk", "sure", "k, I inv", "k i inv",
            "omw", "inv u", "one sec", "coming", "1sec", "1 sec",
            "kkk", "aight", "aight inv", "alright", "alright inv",
            "pull up", "slide trade", "ill trade u", "opening trade",
            "trade time", "sending trade", "im here", "ready when u are");
    private static final List<String> TRADE_THANKS_MSGS = List.of(
            "ty!", "thanks!", "thank you!", "tyty", "appreciate it!", "tysm!",
            "nice ty", "ooh ty!", "thx!!", "much appreciated", "thx", "wow thx", "I owe you one",
            "sweet ty", "ay ty", "perfect ty", "huge ty", "sick ty", "legend");
    private static final List<String> TRADE_FREEBIE_QUIPS = List.of(
            "i better get paid for that eventually lol", "you really should be paying me for that :P",
            "free delivery, where's my tip", "don't say i never gave you anything",
            "i'm basically your personal shopper at this point", "doing this for free smh",
            "enjoy", "hope u like it", "enjoy the loot",
            ":)", ":D", "np", "npnp", "npnpnp", "np man enjoy",
            "there u go", "have fun", "that should help",
            "use it well", "all yours", "take good care of it",
            "delivered", "hope that helps", "treat it nicely", "tell me if you find anything for me too");
    private static final List<String> NO_ITEMS_MSGS = List.of(
            "i don't have any %s",
            "no %s on me rn",
            "don't have any %s right now",
            "i'm out of %s",
            "none of that on me right now",
            "fresh out of %s",
            "wish i had %s but nope",
            "checked, no %s"
    );
    private static final List<String> ALL_DONE_MSGS = List.of(
            "that's all!", "done adding stuff!", "all set!", "everything's in!",
            "that's everything!", "done!", "added it all", "check it out"
    );
    private static final List<String> TRADE_RESERVED_FOR_OTHER_MSGS = List.of(
            "these might be needed by others, maybe don't sell them",
            "careful with these, they could be for someone else",
            "heads up, I was saving those for someone - don't lose them",
            "these might go to someone else, hold onto them for now",
            "those are kinda spoken for, keep them safe ok?",
            "just so you know, I had plans for those"
    );
    private static final List<String> TRADE_RESERVED_FOR_SELF_MSGS = List.of(
            "I might need those later, don't lose them ok?",
            "those could be an upgrade for me eventually, don't toss them",
            "I was thinking I'd use those someday, keep them somewhere",
            "heads up, I kinda wanted those for myself",
            "those might fit me later, maybe hold onto them",
            "just so you know, I had my eye on those"
    );

    static void tickPassiveLoot(BotEntry entry, Character bot) {
        if (entry.lootInhibitMs > 0) {
            entry.lootInhibitMs = BotMovementManager.tickDown(entry.lootInhibitMs);
            return;
        }
        if (entry.pendingTradeCategory != null) {
            return;
        }

        entry.invFullWarnCooldownMs = BotMovementManager.tickDown(entry.invFullWarnCooldownMs);
        Point botPos = bot.getPosition();
        long now = System.currentTimeMillis();
        for (MapItem drop : bot.getMap().getDroppedItems()) {
            if (!BotLootEligibility.isPresent(bot.getMap(), drop)) {
                cleanupBotLootGhostDrop(bot, drop);
                continue;
            }

            Point dropPos = drop.getPosition();
            if (Math.abs(dropPos.x - botPos.x) > BotManager.cfg.LOOT_RADIUS
                    || Math.abs(dropPos.y - botPos.y) > BotManager.cfg.LOOT_RADIUS) {
                continue;
            }

            if (!BotLootEligibility.canBotTargetLoot(entry, bot, bot.getMap(), drop, now)) {
                if (BotLootEligibility.canBotLoot(entry, bot, drop)) {
                    continue;
                }
                if (drop.getMeso() <= 0 && drop.getItemId() > 0) {
                    InventoryType type = ItemConstants.getInventoryType(drop.getItemId());
                    Inventory inventory = bot.getInventory(type);
                    if (inventory != null && inventory.isFull()) {
                        warnInventoryFull(entry, type);
                    }
                }
                continue;
            }

            // NX cards consume on pickup (credit account NX, never enter the bag - Character.pickupItem),
            // so a full ETC inventory must not block them; the player path bypasses the space check
            // for NX cards too. Without this, a bot with a full ETC bag silently loses NX income.
            if (drop.getMeso() <= 0 && drop.getItemId() > 0 && !ItemId.isNxCard(drop.getItemId())) {
                InventoryType type = ItemConstants.getInventoryType(drop.getItemId());
                Inventory inventory = bot.getInventory(type);
                if (inventory != null && inventory.isFull()) {
                    warnInventoryFull(entry, type);
                    continue;
                }
            }

            Item pickedItem = drop.getItem();
            int pickedItemId = drop.getItemId();
            if (BotManager.cfg.REDIRECT_NX_CARDS_TO_OWNER && ItemId.isNxCard(pickedItemId)
                    && entry.owner != null && entry.owner != bot && entry.owner.getMap() == bot.getMap()) {
                entry.owner.pickupItem(drop);
            } else {
                bot.pickupItem(drop);
            }
            cleanupBotLootGhostDrop(bot, drop);
            if (pickedItem != null && pickedItemId > 0 && hasItem(bot, pickedItem)) {
                InventoryType pickedType = ItemConstants.getInventoryType(pickedItemId);
                if (pickedType == InventoryType.EQUIP) {
                    if (BotEquipManager.autoEquip(bot, entry.owner, entry.pendingLootOfferItem)) {
                        // A looted roll just got worn — re-ask stay-or-leave soon (autopilot).
                        BotAutopilotManager.noteGearUpgraded(entry);
                    }
                    if (hasItem(bot, pickedItem)) {
                        BotOfferManager.scheduleLootOfferPrompt(entry, bot, pickedItem, 5_000L);
                    }
                } else if (ItemConstants.isThrowingStar(pickedItemId)) {
                    BotOfferManager.scheduleLootOfferPrompt(entry, bot, pickedItem, 5_000L);
                }
            }
        }
    }

    private static void warnInventoryFull(BotEntry entry, InventoryType type) {
        if (entry.invFullWarnCooldownMs > 0) {
            return;
        }
        BotManager.getInstance().botReply(entry, type.name().toLowerCase() + " inventory is full!");
        entry.invFullWarnCooldownMs = BotMovementManager.delayAfterCurrentTick(BotManager.cfg.INV_FULL_WARN_CD_MS);
    }

    /**
     * Returns the nearest lootable drop within GRIND_SEEK_RANGE, with no region
     * restriction. Returns null when any inventory is full or no eligible drop exists.
     */
    static MapItem findNearestGrindLootTarget(BotEntry entry, Character bot) {
        if (bot == null || hasAnyInventoryFull(bot)) return null;
        MapleMap map = bot.getMap();
        if (map == null) return null;

        long now = System.currentTimeMillis();
        Point botPos = bot.getPosition();
        double seekRangeSq = (double) BotCombatManager.cfg.GRIND_SEEK_RANGE * BotCombatManager.cfg.GRIND_SEEK_RANGE;
        MapItem nearest = null;
        double nearestDistSq = Double.MAX_VALUE;

        for (MapItem drop : map.getDroppedItems()) {
            if (!BotLootEligibility.canBotTargetLoot(entry, bot, map, drop, now)) continue;
            if (BotManager.isGrindLootRetrySuppressed(entry, drop, now)) continue;
            Point dropPos = drop.getPosition();
            if (Math.abs(dropPos.x - botPos.x) <= BotManager.cfg.LOOT_RADIUS
                    && Math.abs(dropPos.y - botPos.y) <= BotManager.cfg.LOOT_RADIUS) {
                continue;
            }
            double distSq = dropPos.distanceSq(botPos);
            if (distSq > seekRangeSq || distSq >= nearestDistSq) continue;
            nearestDistSq = distSq;
            nearest = drop;
        }
        return nearest;
    }

    static boolean hasAnyInventoryFull(Character bot) {
        if (bot == null) return false;
        for (InventoryType type : new InventoryType[]{
                InventoryType.EQUIP, InventoryType.USE, InventoryType.SETUP, InventoryType.ETC}) {
            Inventory inv = bot.getInventory(type);
            if (inv != null && inv.isFull()) return true;
        }
        return false;
    }

    /**
     * Returns the position of the nearest lootable drop within the patrol region
     * and its immediate neighbours (1 graph hop). Returns null when no eligible
     * drop exists, the graph is unavailable, or any inventory is full.
     */
    static Point findNearestPatrolLootTarget(BotEntry entry, int patrolRegionId) {
        Character bot = entry.bot;
        if (bot == null) return null;
        if (hasAnyInventoryFull(bot)) return null;
        MapleMap map = bot.getMap();
        if (map == null) return null;

        BotNavigationGraph graph = BotNavigationGraphProvider.peekBestGraph(map, entry.movementProfile);
        if (graph == null) return null;

        Set<Integer> allowed = new HashSet<>();
        allowed.add(patrolRegionId);
        allowed.addAll(graph.getMutualAdjacentRegionIds(patrolRegionId));

        long now = System.currentTimeMillis();
        Point botPos = bot.getPosition();
        Point nearest = null;
        double nearestDistSq = Double.MAX_VALUE;

        for (MapItem drop : map.getDroppedItems()) {
            if (!BotLootEligibility.canBotTargetLoot(entry, bot, map, drop, now)) continue;
            Point dropPos = drop.getPosition();
            if (!allowed.contains(graph.findRegionId(map, dropPos))) continue;
            double distSq = dropPos.distanceSq(botPos);
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = dropPos;
            }
        }
        return nearest;
    }

    /** A peer bot may auto-join an incoming trade from another bot it shares a stable with: same human
     *  owner (owned stable) OR same crew. Crew bots are SELF-owned (owner == self), so the owner check
     *  fails for them — the crewGroupId is the SSOT relationship. Without the crew arm, crewmate-to-
     *  crewmate trades stalled: the recipient never joined and the donor timed out. See
     *  {@link BotManager#crewMatesOnMap} and kb_bot_self_owned_owner_assumptions. */
    private static boolean isPeerTradePartner(BotEntry entry, Character partnerBot) {
        if (entry.owner != null
                && BotOwnershipService.getInstance().isAuthorizedOwner(partnerBot.getId(), entry.owner.getId())) {
            return true;
        }
        if (entry.crewGroupId != null) {
            BotEntry partnerEntry = BotManager.getInstance().getEntryByBotCharId(partnerBot.getId());
            return partnerEntry != null && entry.crewGroupId.equals(partnerEntry.crewGroupId);
        }
        return false;
    }

    static void tickManualTrade(BotEntry entry, Character bot) {
        if (entry.pendingTradeCategory != null) return;

        Trade trade = bot.getTrade();
        Character commander = BotManager.getInstance().commanderOrOwner(entry);
        if (trade == null) {
            clearManualTradeState(entry, bot);
            return;
        }

        if (trade != entry.manualTradeRef) {
            manualTradeGreetingSent.remove(bot.getId());
            entry.manualTradeAcceptDelayMs = 0;
            entry.manualTradeRef = trade;
            entry.manualTradeTimeoutMs = MANUAL_TRADE_TIMEOUT_MS;
        } else if (entry.manualTradeTimeoutMs > 0) {
            entry.manualTradeTimeoutMs = BotMovementManager.tickDown(entry.manualTradeTimeoutMs);
            if (entry.manualTradeTimeoutMs == 0) {
                Trade.cancelTrade(bot, Trade.TradeResult.NO_RESPONSE);
                clearManualTradeState(entry, bot);
                return;
            }
        }

        if (commander == null) {
            return;
        }

        Trade commanderTrade = commander.getTrade();
        Trade partner = trade.getPartner();
        boolean isCommanderTrade = commanderTrade != null
                && partner == commanderTrade
                && commanderTrade.getPartner() == trade
                && commander.getId() == commanderTrade.getChr().getId();
        if (!isCommanderTrade) {
            // Handle peer-bot trade: a bot in the same stable offering an item to this bot
            boolean isPeerBotTrade = partner != null
                    && partner.getChr().getClient() instanceof client.BotClient
                    && isPeerTradePartner(entry, partner.getChr());
            if (!isPeerBotTrade) {
                manualTradeGreetingSent.remove(bot.getId());
                return;
            }
            // Accept invite if not yet joined — small delay so it feels human
            if (!trade.isFullTrade()) {
                if (trade.getNumber() != 1) return;
                if (entry.manualTradeAcceptDelayMs == 0)
                    entry.manualTradeAcceptDelayMs = 500 + BotMovementManager.cfg.TICK_MS;
                entry.manualTradeAcceptDelayMs = BotMovementManager.tickDown(entry.manualTradeAcceptDelayMs);
                if (entry.manualTradeAcceptDelayMs > 0) return;
                Trade.visitTrade(bot, partner.getChr());
                trade = bot.getTrade();
                if (trade == null || !trade.isFullTrade()) return;
            }
            // Confirm once the offering bot has confirmed its side
            if (trade.isPartnerConfirmed()) {
                completeTradeAndThank(entry, bot, trade);
                BotEquipManager.autoEquip(bot, commander, null);
            }
            return;
        }

        if (!trade.isFullTrade()) {
            // Only accept on bot's behalf when the commander was the initiator (bot is slot 1).
            // When bot is slot 0 (bot initiated via "trade me"), wait for commander to accept.
            if (trade.getNumber() != 1) return;
            if (entry.manualTradeAcceptDelayMs == 0)
                entry.manualTradeAcceptDelayMs = 500 + BotMovementManager.cfg.TICK_MS;
            entry.manualTradeAcceptDelayMs = BotMovementManager.tickDown(entry.manualTradeAcceptDelayMs);
            if (entry.manualTradeAcceptDelayMs > 0) return;
            Trade.visitTrade(bot, commander);
            trade = bot.getTrade();
            if (trade == null || !trade.isFullTrade()) return;
        }

        if (manualTradeGreetingSent.add(bot.getId())) {
            trade.chat(BotManager.getInstance().manualTradeGreeting());
        }

        if (trade.isPartnerConfirmed()) {
            completeTradeAndThank(entry, bot, trade);
            BotEquipManager.autoEquip(bot, commander, null);
        }
    }

    // ─── Entry point from chat choice ─────────────────────────────────────────

    private static void cleanupBotLootGhostDrop(Character bot, MapItem drop) {
        if (drop == null) {
            return;
        }
        if (!drop.isPickedUp() && bot.getMap().getMapObject(drop.getObjectId()) == drop) {
            return;
        }

        Packet removePacket = PacketCreator.removeItemFromMap(drop.getObjectId(), 1, 0);
        for (Character player : bot.getMap().getAllPlayers()) {
            if (player.getClient() instanceof BotClient) {
                continue;
            }
            if (!player.isMapObjectVisible(drop)) {
                continue;
            }
            player.removeVisibleMapObject(drop);
            player.sendPacket(removePacket);
        }
    }

    /**
     * Called after the owner chooses "drop" or "trade" in the item-choice prompt.
     * category: "scrolls", "pots", "equips", "etc", or "name:<fragment>"
     */
    static void executeChoice(String category, boolean tradeToOwner, BotEntry entry, Character bot) {
        if (tradeToOwner) {
            startTradeTransfer(category, entry, bot);
        } else {
            dropCategory(category, entry, bot);
            entry.lootInhibitMs = BotMovementManager.delayAfterCurrentTick(20_000); // ~20s: prevents bot re-looting its own floor drops
        }
    }

    private static void dropCategory(String category, BotEntry entry, Character bot) {
        // When items aren't globally tradable, drops on drop-limited maps (e.g. free market)
        // silently vanish. Refuse and tell the owner why instead of destroying the items.
        if (!YamlConfig.config.server.UNTRADEABLE_ITEMS_TRADEABLE
                && FieldLimit.DROP_LIMIT.check(bot.getMap().getFieldLimit())) {
            BotManager.getInstance().botReply(entry, "can't drop here - try another map or trade");
            return;
        }
        switch (category) {
            case "scrolls" -> dropScrolls(entry, bot);
            case "pots"    -> dropPotions(entry, bot);
            case "buff"    -> dropBuffPots(entry, bot);
            case "equips"  -> dropEquips(entry, bot);
            case "trash"   -> dropTrashEquips(entry, bot);
            case "etc"     -> dropEtc(entry, bot);
            default -> { if (category.startsWith("name:")) dropByName(entry, bot, category.substring(5)); }
        }
    }

    // ─── Trade actions (actual trade window) ─────────────────────────────────

    /**
     * Kicks off a trade sequence for the given category.
     * Items are batched ≤9 per trade window; subsequent batches open new trades automatically.
     */
    static void startTradeTransfer(String category, BotEntry entry, Character bot) {
        long startedAt = profileTradeCategory(category) ? System.nanoTime() : 0L;
        if (isMesoCategory(category)) {
            startTradeMesoTransfer(category, entry, bot);
            return;
        }
        // Trade with the bound admin commander when one issued the command (gm debug-inspecting a bot
        // it doesn't own), else the real owner. commanderOrOwner is the SSOT for this resolution.
        Character owner = BotManager.getInstance().commanderOrOwner(entry);
        if (owner == null) {
            BotManager.getInstance().botReply(entry, "can't find you to trade!");
            return;
        }
        if (bot.getTrade() != null || entry.pendingTradeCategory != null) {
            BotManager.getInstance().botReply(entry, "already in a trade!");
            return;
        }
        if (owner.getTrade() != null) {
            BotManager.getInstance().botReply(entry, "you're already in a trade!");
            return;
        }
        if ("equips".equals(category)) {
            long equipsStartedAt = startedAt != 0L ? System.nanoTime() : 0L;
            startEquipsGroupTradeTransfer(owner, entry, bot);
            logSlowTradeCommand(category, "startEquipsGroupTradeTransfer", entry, bot, equipsStartedAt);
            logSlowTradeCommand(category, "startTradeTransfer", entry, bot, startedAt);
            return;
        }
        if (isReservedEquipsCategory(category)) {
            List<Item> items = collectReservedEquipTradePage(category, entry, bot);
            if (items.isEmpty()) {
                BotManager.getInstance().botReply(entry, noItemsReply(category));
                return;
            }
            startTradeSequence(category, owner, items, 0, true, entry, bot);
            entry.pendingTradeCategoryMsg = reservedEquipsPageMessage(category, entry, bot);
            logSlowTradeCommand(category, "startTradeTransfer", entry, bot, startedAt);
            return;
        }
        if ("ammo".equals(category)) {
            startAmmoGroupTradeTransfer(owner, entry, bot);
            logSlowTradeCommand(category, "startTradeTransfer", entry, bot, startedAt);
            return;
        }
        long prepareStartedAt = startedAt != 0L ? System.nanoTime() : 0L;
        PreparedTradeItems prepared = prepareTradeItems(category, entry, bot);
        logSlowTradeCommand(category, "prepareTradeItems", entry, bot, prepareStartedAt);
        if (prepared.errorMessage() != null) {
            BotManager.getInstance().botReply(entry, prepared.errorMessage());
            return;
        }
        List<Item> items = prepared.items();
        if (items.isEmpty()) {
            BotManager.getInstance().botReply(entry, noItemsReply(category));
            return;
        }
        startTradeSequence(category, owner, items, 0, !entry.pendingTradeRestoreSlots.isEmpty(), entry, bot);
        logSlowTradeCommand(category, "startTradeTransfer", entry, bot, startedAt);
    }

    static void startTradeTransfer(Item item, Character recipient, BotEntry entry, Character bot) {
        if (recipient == null) {
            BotManager.getInstance().botReply(entry, "can't find who to trade!");
            return;
        }
        if (!hasItem(bot, item)) {
            BotManager.getInstance().botReply(entry, "don't have it anymore");
            return;
        }
        if (bot.getTrade() != null || entry.pendingTradeCategory != null || recipient.getTrade() != null) {
            if (entry.pendingBotTradeRetry == null) {
                entry.pendingBotTradeRetry = () -> startTradeTransfer(item, recipient, entry, bot);
                entry.pendingBotTradeRetryMs = BotMovementManager.delayAfterCurrentTick(10_000);
            }
            return;
        }
        startTradeSequence("loot_offer", recipient, List.of(item), 0, true, entry, bot);
    }

    /**
     * "Let me see": put the proposed scroll-target equip + the scroll into a trade with the owner so
     * they can decide -- decline (items return) or accept (take them and scroll it themself). A worn
     * target is first moved to the bag with its slot registered in {@code pendingTradeRestoreSlots},
     * so a declined trade re-equips it via the standard restore net; an accepted trade just consumes
     * it (restore skips items the bot no longer has).
     */
    static void startScrollReviewTrade(BotEntry entry, Character bot, Item equip, Item scroll) {
        if (entry == null || bot == null || !(equip instanceof Equip) || scroll == null) {
            return;
        }
        // Show it to whoever asked: the admin commander while a debug binding is fresh, else the real
        // owner (mirrors resolveTradeRecipient). Using entry.owner here sent the "let me see" trade to
        // the absent owner when an admin requested it, so nothing opened for the admin.
        Character owner = BotManager.getInstance().commanderOrOwner(entry);
        if (owner == null || owner == bot) {
            BotManager.getInstance().botReply(entry, "no one here to show it to");
            return;
        }
        if (bot.getTrade() != null || entry.pendingTradeCategory != null || owner.getTrade() != null) {
            BotManager.getInstance().botReply(entry, "cant open a trade rn, ask again in a bit");
            return;
        }
        if (!hasItem(bot, equip) || !hasItem(bot, scroll)) {
            BotManager.getInstance().botReply(entry, "nvm, my inventory changed");
            return;
        }
        Item tradeEquip = equip;
        if (equip.getPosition() < 0) { // worn -> pull into the bag so it can be traded
            Inventory equipBag = bot.getInventory(InventoryType.EQUIP);
            short src = equip.getPosition();
            short dst = equipBag.getNextFreeSlot();
            if (dst < 0) {
                BotManager.getInstance().botReply(entry, "my equip bag's full, cant pull it off to show you");
                return;
            }
            InventoryManipulator.handleItemMove(bot.getClient(), InventoryType.EQUIP, src, dst, (short) 1);
            Item moved = equipBag.getItem(dst);
            if (moved == null) {
                BotManager.getInstance().botReply(entry, "couldnt get it ready, nvm");
                return;
            }
            entry.pendingTradeRestoreSlots.put(moved, src);
            tradeEquip = moved;
        }
        BotManager.getInstance().botReply(entry, "sure, take a look - here's the gear + the scroll");
        startTradeSequence("scroll_review", owner, List.of(tradeEquip, scroll), 0, true, entry, bot);
    }

    static boolean hasTransferableItems(String category, BotEntry entry, Character bot) {
        if (isMesoCategory(category)) {
            int currentMesos = bot.getMeso();
            if (currentMesos <= 0) {
                return false;
            }

            int requestedMesos = requestedTradeMesos(category);
            return requestedMesos <= 0 || currentMesos >= requestedMesos;
        }

        if (category != null && category.startsWith("name:")) {
            String fragment = category.substring(5);
            if (hasEquippedSlotItems(bot, fragment)) {
                return true;
            }
        }

        return !collectItems(category, entry, bot).isEmpty();
    }

    static boolean profileTradeCategory(String category) {
        return "trash".equals(category) || "equips".equals(category) || isReservedEquipsCategory(category);
    }

    static void logSlowTradeCommand(String category, String phase, BotEntry entry, Character bot, long startedAt) {
        if (startedAt == 0L || !profileTradeCategory(category)) {
            return;
        }
        long elapsedNs = System.nanoTime() - startedAt;
        if (elapsedNs < TRADE_COMMAND_PROFILE_WARN_NS) {
            return;
        }
        String botName = bot != null ? bot.getName() : "?";
        String ownerName = entry != null && entry.owner != null ? entry.owner.getName() : "?";
        log.warn("Slow bot trade command phase: category={} phase={} took {} ms bot={} owner={}",
                category,
                phase,
                String.format("%.1f", elapsedNs / 1_000_000.0),
                botName,
                ownerName);
    }

    static int countTransferableItems(String category, BotEntry entry, Character bot) {
        if (isMesoCategory(category)) {
            return bot.getMeso();
        }
        if (category != null && category.startsWith("name:")) {
            String fragment = category.substring(5);
            int total = countNamedItems(fragment, bot);
            short[] slots = BotEquipManager.slotsFromName(fragment);
            if (slots.length > 0) {
                Inventory equipped = bot.getInventory(InventoryType.EQUIPPED);
                ItemInformationProvider ii = ItemInformationProvider.getInstance();
                for (short slot : slots) {
                    Item item = equipped.getItem(slot);
                    if (item != null && !ii.isCash(item.getItemId())) {
                        total++;
                    }
                }
            }
            return total;
        }
        return itemQuantitySum(collectItems(category, entry, bot));
    }

    private static int countNamedItems(String fragment, Character bot) {
        return itemQuantitySum(collectNamedItems(fragment, bot));
    }

    private static int itemQuantitySum(List<Item> items) {
        int total = 0;
        for (Item item : items) {
            total += item.getInventoryType() == InventoryType.EQUIP ? 1 : Math.max(0, item.getQuantity());
        }
        return total;
    }

    static String noItemsReply(String category) {
        String what = switch (category) {
            case "mesos" -> "mesos";
            case "recommended" -> "better gear for you";
            case "scrolls" -> "scrolls";
            case "pots" -> "pots";
            case "buff" -> "buff pots";
            case "use" -> "use items";
            case "ammo" -> "ammo";
            case "equips" -> "equips";
            case "trash" -> "trash equips";
            case "etc" -> "etc items";
            default -> {
                if (isReservedEquipsCategory(category)) {
                    yield "reserved equips";
                }
                if (category.startsWith("mesos:")) {
                    yield "mesos";
                }
                yield category.startsWith("name:") ? category.substring(5) : "those items";
            }
        };

        String fmt = BotManager.randomReply(NO_ITEMS_MSGS);
        return String.format(fmt, what);
    }

    /** Opens a trade for the first ≤9 items; remaining items are re-collected next batch. */
    private static void startTradeSequence(String category,
                                           Character recipient,
                                           List<Item> items,
                                           int mesos,
                                           boolean singleBatch,
                                           BotEntry entry,
                                           Character bot) {
        if (recipient == null) {
            BotManager.getInstance().botReply(entry, "can't find who to trade!");
            return;
        }
        entry.pendingTradeCategory = category;
        entry.pendingTradeRecipientId = recipient.getId();
        entry.pendingTradeSingleBatch = singleBatch;
        entry.pendingTradeInviteAnnounced = false;
        openTradeBatch(entry, bot, items, mesos);
    }

    private static void openTradeBatch(BotEntry entry, Character bot, List<Item> items, int mesos) {
        Character recipient = resolveTradeRecipient(entry, bot);
        if (recipient == null || recipient.getTrade() != null) {
            cancelTradeSequence(entry, bot, "can't trade right now, stopping");
            return;
        }
        entry.pendingTradeItems = items.size() > TRADE_WINDOW_ITEM_LIMIT
                ? new ArrayList<>(items.subList(0, TRADE_WINDOW_ITEM_LIMIT))
                : new ArrayList<>(items);
        entry.pendingTradeMeso     = mesos;
        entry.pendingTradeIdx      = 0;
        entry.pendingTradeTimerMs  = 0;
        entry.pendingTradeMesoAdded = false;
        entry.pendingTradeAllAdded = false;
        entry.pendingTradeBotDone  = false;
        Trade.startTrade(bot);
        Trade.inviteTrade(bot, recipient);
        // pot_share already announced itself ("got some HP pots, inv u") — skip the redundant "k i inv"
        if (!entry.pendingTradeInviteAnnounced
                && !"pot_share".equals(entry.pendingTradeCategory)
                && !"ammo_share".equals(entry.pendingTradeCategory)) {
            entry.pendingTradeInviteAnnounced = true;
            BotManager.getInstance().botReply(entry, BotManager.randomReply(TRADE_INVITATION_MSGS));
        }
    }

    /** Called every bot simulation tick while a trade sequence is in progress. */
    static void tickTrade(BotEntry entry, Character bot) {
        // Fire a queued bot-initiated retry once this bot is free and the delay expires.
        if (entry.pendingTradeCategory == null && entry.pendingBotTradeRetry != null) {
            if (entry.pendingBotTradeRetryMs > 0) {
                entry.pendingBotTradeRetryMs = BotMovementManager.tickDown(entry.pendingBotTradeRetryMs);
                return;
            }
            Runnable retry = entry.pendingBotTradeRetry;
            entry.pendingBotTradeRetry = null;
            retry.run();
            return;
        }
        if (entry.pendingTradeCategory == null) return;

        Trade trade = bot.getTrade();

        // ── PAUSE between batches (items == null) ──────────────────────────
        if (entry.pendingTradeItems == null) {
            if (entry.pendingTradeSingleBatch) {
                resetTradeState(entry, bot);
                return;
            }
            if (entry.pendingTradeTimerMs > 0) {
                entry.pendingTradeTimerMs = BotMovementManager.tickDown(entry.pendingTradeTimerMs);
                return;
            }
            List<Item> next = collectItems(entry.pendingTradeCategory, entry, bot);
            if (next.isEmpty()) {
                String advanced = nextEquipsGroup(entry.pendingTradeCategory, entry, bot);
                if (advanced == null) {
                    advanced = nextAmmoGroup(entry.pendingTradeCategory, bot);
                }
                if (advanced != null) {
                    entry.pendingTradeCategory = advanced;
                    entry.pendingTradeCategoryMsg = equipsGroupMsg(advanced);
                    openTradeBatch(entry, bot, collectItems(advanced, entry, bot), 0);
                } else {
                    resetTradeState(entry, bot);
                }
            } else {
                openTradeBatch(entry, bot, next, 0);
            }
            return;
        }

        // ── Trade was closed externally ────────────────────────────────────
        if (trade == null) {
            if (entry.pendingTradeBotDone) {
                // Both sides confirmed — sequence complete or cancelled after bot OK
                if (entry.pendingTradeSingleBatch) {
                    resetTradeState(entry, bot);
                    BotEquipManager.autoEquip(bot, entry.owner, null);
                    return;
                }
                entry.pendingTradeItems    = null;
                entry.pendingTradeAllAdded = false;
                entry.pendingTradeBotDone  = false;
                entry.pendingTradeTimerMs  = BotMovementManager.delayAfterCurrentTick(1_000);
            } else if (entry.pendingTradeAllAdded) {
                // Owner cancelled after items were added (items returned to bot)
                BotManager.getInstance().botReply(entry, "trade cancelled");
                resetTradeState(entry, bot);
                BotEquipManager.autoEquip(bot, entry.owner, null);
            } else {
                // Owner declined invite
                BotManager.getInstance().botReply(entry, "trade declined");
                resetTradeState(entry, bot);
            }
            return;
        }

        // ── WAITING FOR ACCEPT ────────────────────────────────────────────
        if (!trade.isFullTrade()) {
            entry.pendingTradeTimerMs += BotMovementManager.cfg.TICK_MS;
            if (entry.pendingTradeTimerMs > 30_000) {
                BotManager.getInstance().botReply(entry, "trade request timed out");
                Trade.cancelTrade(bot, Trade.TradeResult.NO_RESPONSE);
                resetTradeState(entry, bot);
            }
            return;
        }

        // ── ADDING ITEMS ──────────────────────────────────────────────────
        if (!entry.pendingTradeAllAdded) {
            if (entry.pendingTradeTimerMs > 0) {
                entry.pendingTradeTimerMs = BotMovementManager.tickDown(entry.pendingTradeTimerMs);
                return;
            }

            if (!entry.pendingTradeMesoAdded && entry.pendingTradeMeso > 0) {
                if (bot.getMeso() < entry.pendingTradeMeso) {
                    cancelTradeSequence(entry, bot, "don't have that many mesos anymore");
                    return;
                }

                trade.setMeso(entry.pendingTradeMeso);
                entry.pendingTradeMesoAdded = true;
                entry.pendingTradeTimerMs = BotMovementManager.delayAfterCurrentTick(500);
                return;
            }

            List<Item> items = entry.pendingTradeItems;
            int idx = entry.pendingTradeIdx;

            if (idx >= items.size()) {
                // All items added — say so in trade chat and wait for owner OK
                entry.pendingTradeAllAdded = true;
                entry.pendingTradeTimerMs  = 0;
                String msg = BotManager.randomReply(ALL_DONE_MSGS);
                trade.chat(msg);
                return;
            }

            // Send group announcement before the first item
            if (idx == 0 && entry.pendingTradeCategoryMsg != null) {
                trade.chat(entry.pendingTradeCategoryMsg);
                entry.pendingTradeCategoryMsg = null;
                entry.pendingTradeTimerMs = BotMovementManager.delayAfterCurrentTick(600);
                return;
            }

            // Add next item
            Item item = items.get(idx);
            entry.pendingTradeIdx++;
            entry.pendingTradeTimerMs = BotMovementManager.delayAfterCurrentTick(500); // 500 ms before next

            short tradeQty = capTradeQuantityByShareBudget(entry, item.getQuantity());

            InventoryType invType = item.getInventoryType();
            Inventory inv = bot.getInventory(invType);
            inv.lockInventory();
            try {
                Item current  = inv.getItem(item.getPosition());
                if (current == null || current != item) return; // slot changed, skip

                Item tradeItem = item.copy();
                tradeItem.setPosition((short) (idx + 1)); // trade-window slot 1-9
                tradeItem.setQuantity(tradeQty);

                if (trade.addItem(tradeItem)) {
                    rememberTradeWindowItemForRestore(entry, item, tradeItem);
                    InventoryManipulator.removeFromSlot(bot.getClient(),
                            invType, item.getPosition(), tradeQty, false);
                    bot.sendPacket(PacketCreator.getTradeItemAdd((byte) 0, tradeItem));
                    if (trade.getPartner() != null) {
                        trade.getPartner().getChr().sendPacket(PacketCreator.getTradeItemAdd((byte) 1, tradeItem));
                    }
                }
            } finally {
                inv.unlockInventory();
            }
            return;
        }

        // ── WAITING FOR OWNER TO CLICK OK ─────────────────────────────────
        if (!entry.pendingTradeBotDone) {
            entry.pendingTradeTimerMs += BotMovementManager.cfg.TICK_MS;
            Character recipient = resolveTradeRecipient(entry, bot);
            boolean recipientIsBot = recipient != null && recipient.getClient() instanceof client.BotClient;
            if (recipientIsBot || trade.isPartnerConfirmed()) {
                completeTradeAndThank(entry, bot, trade);
                entry.pendingTradeBotDone = true;
                entry.pendingTradeTimerMs = 0;
            } else if (entry.pendingTradeTimerMs > 60_000) { // 60 s timeout
                BotManager.getInstance().botReply(entry, "trade timed out, cancelling");
                Trade.cancelTrade(bot, Trade.TradeResult.NO_RESPONSE);
                resetTradeState(entry, bot);
            }
        }
        // pendingTradeBotDone=true: wait for bot.getTrade() to become null (handled above)
    }

    private static void cancelTradeSequence(BotEntry entry, Character bot, String msg) {
        BotManager.getInstance().botReply(entry, msg);
        if (bot.getTrade() != null) Trade.cancelTrade(bot, Trade.TradeResult.NO_RESPONSE);
        resetTradeState(entry, bot);
    }

    private static void clearManualTradeState(BotEntry entry, Character bot) {
        manualTradeGreetingSent.remove(bot.getId());
        entry.manualTradeAcceptDelayMs = 0;
        entry.manualTradeRef = null;
        entry.manualTradeTimeoutMs = 0;
    }

    private static void resetTradeState(BotEntry entry, Character bot) {
        boolean hadRestores = !entry.pendingTradeRestoreSlots.isEmpty();
        restoreTemporarilyUnequippedItems(entry, bot);
        clearManualTradeState(entry, bot);
        entry.pendingTradeCategory = null;
        entry.pendingTradeCategoryMsg = null;
        entry.pendingTradeItems    = null;
        entry.pendingTradeRecipientId = 0;
        entry.pendingTradeMeso     = 0;
        entry.pendingTradeIdx      = 0;
        entry.pendingTradeTimerMs  = 0;
        entry.pendingTradeMesoAdded = false;
        entry.pendingTradeAllAdded = false;
        entry.pendingTradeBotDone  = false;
        entry.pendingTradeSingleBatch = false;
        entry.pendingTradeInviteAnnounced = false;
        entry.pendingPotShareBudget = 0;
        entry.ownerGivenItems.clear();
        // Safety net: if any items were temporarily unequipped for a trade that ended without
        // completing (declined invite / cancel / timeout), the per-slot restore above may fail
        // (slot occupied, item lost via window-swap bookkeeping). Re-run autoEquip so empty
        // slots get refilled from the bot's bag — prevents leaving the bot wearing e.g. pants
        // without a top after a declined trade.
        if (hadRestores && bot != null) {
            BotEquipManager.autoEquip(bot, entry.owner, null);
        }
    }

    static void rememberTradeWindowItemForRestore(BotEntry entry, Item inventoryItem, Item tradeItem) {
        Short restoreSlot = entry.pendingTradeRestoreSlots.remove(inventoryItem);
        if (restoreSlot != null) {
            entry.pendingTradeRestoreSlots.put(tradeItem, restoreSlot);
        }
    }

    static short capTradeQuantityByShareBudget(BotEntry entry, short availableQty) {
        if (entry.pendingPotShareBudget <= 0) {
            return availableQty;
        }
        short tradeQty = (short) Math.min(availableQty, entry.pendingPotShareBudget);
        entry.pendingPotShareBudget -= tradeQty;
        return tradeQty;
    }

    private static void completeTradeAndThank(BotEntry entry, Character bot, Trade trade) {
        // Snapshot equips the owner is giving us before the trade clears their side.
        // Trade reuses the same item objects, so identity comparison in ownerGivenItems works.
        if (trade.getPartner() != null) {
            for (Item item : trade.getPartner().getItems()) {
                if (ItemConstants.getInventoryType(item.getItemId()) == InventoryType.EQUIP) {
                    entry.ownerGivenItems.add(item);
                }
            }
        }
        boolean receivedSomething = trade.getPartner() != null && trade.getPartner().hasAnyOffer();
        Trade.completeTrade(bot);
        sortOwnAmmoSlots(bot);
        long replyDelay = BotManager.randMs(800, 1300);
        if (receivedSomething) {
            bot.changeFaceExpression(Emote.HAPPY.getValue());
            BotManager.after(replyDelay, () ->
                    BotManager.getInstance().botSay(entry, BotManager.randomReply(TRADE_THANKS_MSGS)));
        } else if (ThreadLocalRandom.current().nextInt(100) < 20) {
            bot.changeFaceExpression(ThreadLocalRandom.current().nextBoolean() ? Emote.GLARE.getValue() : Emote.ANNOYED.getValue());
            BotManager.after(replyDelay, () ->
                    BotManager.getInstance().botSay(entry, BotManager.randomReply(TRADE_FREEBIE_QUIPS)));
        }
    }

    private static void startTradeMesoTransfer(String category, BotEntry entry, Character bot) {
        // Honor the bound admin commander (gm debug) over the real owner, like startTradeTransfer.
        Character owner = BotManager.getInstance().commanderOrOwner(entry);
        if (owner == null) {
            BotManager.getInstance().botReply(entry, "can't find you to trade!");
            return;
        }
        if (bot.getTrade() != null || entry.pendingTradeCategory != null) {
            BotManager.getInstance().botReply(entry, "already in a trade!");
            return;
        }
        if (owner.getTrade() != null) {
            BotManager.getInstance().botReply(entry, "you're already in a trade!");
            return;
        }

        int currentMesos = bot.getMeso();
        if (currentMesos <= 0) {
            BotManager.getInstance().botReply(entry, noItemsReply(category));
            return;
        }

        int requestedMesos = requestedTradeMesos(category);
        if (requestedMesos == 0) {
            BotManager.getInstance().botReply(entry, "ask for more than 0 mesos, or just say 'trade mesos'");
            return;
        }
        if (requestedMesos > 0 && currentMesos < requestedMesos) {
            BotManager.getInstance().botReply(entry, notEnoughMesosReply(requestedMesos, currentMesos));
            return;
        }

        startTradeSequence(category, owner, List.of(), requestedMesos > 0 ? requestedMesos : currentMesos, true, entry, bot);
    }

    // ─── Item collection helpers ──────────────────────────────────────────────

    private static PreparedTradeItems prepareTradeItems(String category, BotEntry entry, Character bot) {
        if (category != null && category.startsWith("name:")) {
            String fragment = category.substring(5).trim();
            PreparedTradeItems equippedSlotItems = prepareEquippedSlotTradeItems(fragment, entry, bot);
            if (equippedSlotItems.errorMessage() != null || !equippedSlotItems.items().isEmpty()) {
                return equippedSlotItems;
            }
            return new PreparedTradeItems(collectNamedItems(fragment, bot), null);
        }

        return new PreparedTradeItems(collectItems(category, entry, bot), null);
    }

    static List<Item> prioritizeEtcTradeItems(List<Item> items, Character recipient) {
        if (items.size() <= 1) {
            return items;
        }

        List<Item> serverSortedItems = sortItemsByItemId(items);
        if (recipient == null) {
            return serverSortedItems;
        }

        Inventory recipientEtc = recipient.getInventory(InventoryType.ETC);
        if (recipientEtc == null) {
            return serverSortedItems;
        }

        Set<Integer> recipientEtcItemIds = new HashSet<>();
        for (Item recipientItem : recipientEtc) {
            recipientEtcItemIds.add(recipientItem.getItemId());
        }

        List<Item> prioritized = new ArrayList<>(items.size());
        List<Item> remainder = new ArrayList<>(items.size());
        for (Item item : serverSortedItems) {
            if (item.getInventoryType() == InventoryType.ETC && recipientEtcItemIds.contains(item.getItemId())) {
                prioritized.add(item);
            } else {
                remainder.add(item);
            }
        }
        prioritized.addAll(remainder);
        return prioritized;
    }

    private static List<Item> prioritizeRecipientDuplicateItemIds(List<Item> items,
                                                                  InventoryType type,
                                                                  Character recipient) {
        if (items.size() <= 1) {
            return items;
        }

        List<Item> sorted = sortItemsByItemId(items);
        if (recipient == null) {
            return sorted;
        }

        Inventory recipientInventory = recipient.getInventory(type);
        if (recipientInventory == null) {
            return sorted;
        }

        Set<Integer> recipientItemIds = new HashSet<>();
        for (Item recipientItem : recipientInventory) {
            recipientItemIds.add(recipientItem.getItemId());
        }

        List<Item> prioritized = new ArrayList<>(items.size());
        List<Item> remainder = new ArrayList<>(items.size());
        for (Item item : sorted) {
            if (recipientItemIds.contains(item.getItemId())) {
                prioritized.add(item);
            } else {
                remainder.add(item);
            }
        }
        prioritized.addAll(remainder);
        return prioritized;
    }

    static List<Item> prioritizeTradeUseItems(List<Item> uncategorized,
                                              List<Item> categorizedOther,
                                              List<Item> potionAmmo,
                                              Character recipient) {
        List<Item> ordered = new ArrayList<>(
                uncategorized.size() + categorizedOther.size() + potionAmmo.size());
        ordered.addAll(prioritizeRecipientDuplicateItemIds(uncategorized, InventoryType.USE, recipient));
        ordered.addAll(prioritizeRecipientDuplicateItemIds(categorizedOther, InventoryType.USE, recipient));
        ordered.addAll(prioritizeRecipientDuplicateItemIds(potionAmmo, InventoryType.USE, recipient));
        return ordered;
    }

    static List<Item> prioritizeScrollTradeItems(List<Item> items, Character recipient) {
        return prioritizeRecipientDuplicateItemIds(items, InventoryType.USE, recipient);
    }

    private static List<Item> collectNamedItems(String fragment, Character bot) {
        List<Item> result = new ArrayList<>();
        String normalizedFragment = normalizeItemQuery(fragment);
        for (InventoryType t : List.of(
                InventoryType.EQUIP, InventoryType.USE, InventoryType.ETC, InventoryType.SETUP)) {
            collectFromBag(bot, result, t, item -> {
                String name = normalizedItemName(item.getItemId());
                return name != null && name.contains(normalizedFragment);
            });
        }
        return result;
    }

    private static String normalizedItemName(int itemId) {
        return normalizedItemNameCache.computeIfAbsent(itemId, BotInventoryManager::loadNormalizedItemName);
    }

    private static String loadNormalizedItemName(int itemId) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        String name;
        synchronized (ii) {
            name = ii.getName(itemId);
        }
        return name != null ? normalizeItemQuery(name) : "";
    }

    static String normalizeItemQuery(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.toLowerCase()
                .replaceAll("[?!.,]+$", "")
                .replaceAll("[^a-z0-9 '\\-]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
        if (normalized.isEmpty()) {
            return "";
        }
        List<String> tokens = new ArrayList<>(List.of(normalized.split(" ")));
        int lastIndex = tokens.size() - 1;
        tokens.set(lastIndex, singularizeToken(tokens.get(lastIndex)));
        return String.join(" ", tokens).trim();
    }

    private static String singularizeToken(String token) {
        if (token.length() <= 3 || !token.endsWith("s")) {
            return token;
        }
        if (token.endsWith("ies") && token.length() > 4) {
            return token.substring(0, token.length() - 3) + "y";
        }
        return token.substring(0, token.length() - 1);
    }

    private static boolean hasEquippedSlotItems(Character bot, String fragment) {
        short[] slots = BotEquipManager.slotsFromName(fragment);
        if (slots.length == 0) {
            return false;
        }

        Inventory equipped = bot.getInventory(InventoryType.EQUIPPED);
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        for (short slot : slots) {
            Item item = equipped.getItem(slot);
            if (item != null && !ii.isCash(item.getItemId())) {
                return true;
            }
        }
        return false;
    }

    private static PreparedTradeItems prepareEquippedSlotTradeItems(String fragment, BotEntry entry, Character bot) {
        short[] slots = BotEquipManager.slotsFromName(fragment);
        if (slots.length == 0) {
            return new PreparedTradeItems(List.of(), null);
        }

        Inventory equipped = bot.getInventory(InventoryType.EQUIPPED);
        Inventory equipBag = bot.getInventory(InventoryType.EQUIP);
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        List<Short> occupiedSlots = new ArrayList<>();
        for (short slot : slots) {
            Item item = equipped.getItem(slot);
            if (item != null && !ii.isCash(item.getItemId())) {
                occupiedSlots.add(slot);
            }
        }
        if (occupiedSlots.isEmpty()) {
            return new PreparedTradeItems(List.of(), null);
        }
        if (equipBag.getNumFreeSlot() < occupiedSlots.size()) {
            return new PreparedTradeItems(List.of(), "equip bag full");
        }

        occupiedSlots.sort(Short::compare);
        List<Item> result = new ArrayList<>();
        for (short srcSlot : occupiedSlots) {
            short dstSlot = equipBag.getNextFreeSlot();
            if (dstSlot < 0) {
                restoreTemporarilyUnequippedItems(entry, bot);
                return new PreparedTradeItems(List.of(), "ran out of equip slots");
            }

            InventoryManipulator.handleItemMove(bot.getClient(), InventoryType.EQUIP, srcSlot, dstSlot, (short) 1);
            Item moved = equipBag.getItem(dstSlot);
            if (moved == null) {
                restoreTemporarilyUnequippedItems(entry, bot);
                return new PreparedTradeItems(List.of(), "couldn't prepare equipped item for trade");
            }

            entry.pendingTradeRestoreSlots.put(moved, srcSlot);
            result.add(moved);
        }

        return new PreparedTradeItems(result, null);
    }

    private static void restoreTemporarilyUnequippedItems(BotEntry entry, Character bot) {
        if (bot == null || entry.pendingTradeRestoreSlots.isEmpty()) {
            entry.pendingTradeRestoreSlots.clear();
            return;
        }

        Inventory equipped = bot.getInventory(InventoryType.EQUIPPED);
        List<Map.Entry<Item, Short>> restoreEntries = new ArrayList<>(entry.pendingTradeRestoreSlots.entrySet());
        restoreEntries.sort(Comparator.comparingInt(Map.Entry::getValue));
        for (Map.Entry<Item, Short> restoreEntry : restoreEntries) {
            Item item = restoreEntry.getKey();
            short dstSlot = restoreEntry.getValue();
            if (!hasItem(bot, item) || equipped.getItem(dstSlot) != null) {
                continue;
            }
            InventoryManipulator.handleItemMove(bot.getClient(), InventoryType.EQUIP, item.getPosition(), dstSlot, (short) 1);
        }
        entry.pendingTradeRestoreSlots.clear();
    }

    private static List<Item> collectItems(String category, BotEntry entry, Character bot) {
        List<Item> result = new ArrayList<>();
        switch (category) {
            case "recommended" -> {
                Character owner = entry.owner;
                if (owner != null) {
                    result.addAll(BotEquipManager.collectRecommendedItems(owner, bot));
                }
            }
            case "scrolls" -> {
                collectFromBag(bot, result, InventoryType.USE,
                        item -> ItemConstants.isEquipScroll(item.getItemId()));
                result = prioritizeScrollTradeItems(result, entry.owner);
            }
            case "pots"    -> collectFromBag(bot, result, InventoryType.USE,
                    item -> isRecoveryPotion(item.getItemId()));
            case "buff"    -> collectFromBag(bot, result, InventoryType.USE,
                    item -> isBuffConsumable(item.getItemId()));
            case "use"     -> {
                UseTradeGroups groups = classifyUseTradeGroups(bot, entry.owner);
                result.addAll(groups.uncategorized());
                result.addAll(groups.categorized());
            }
            case "ammo" -> {
                AmmoTradeGroups groups = classifyAmmoTradeGroups(bot);
                result.addAll(groups.nonOwn());
                result.addAll(groups.own());
            }
            case "equips" -> {
                EquipTradeGroups groups = classifyEquipTradeGroups(entry, bot);
                for (EquipsGroup g : EquipsGroup.values()) result.addAll(groups.itemsFor(g));
            }
            case "trash" -> result.addAll(collectTrashEquips(entry, bot));
            case "etc" -> {
                collectFromBag(bot, result, InventoryType.ETC, item -> true);
                result = prioritizeEtcTradeItems(result, entry.owner);
            }
            default -> {
                if (isReservedEquipsCategory(category)) {
                    result.addAll(collectReservedEquipTradePage(category, entry, bot));
                } else {
                    EquipsGroup eg = EquipsGroup.fromCategory(category);
                    if (eg != null) {
                        result.addAll(classifyEquipTradeGroups(entry, bot).itemsFor(eg));
                    } else {
                        AmmoGroup ammoGroup = AmmoGroup.fromCategory(category);
                        if (ammoGroup != null) {
                            result.addAll(classifyAmmoTradeGroups(bot).itemsFor(ammoGroup));
                        } else if (category.startsWith("name:")) {
                            result.addAll(collectNamedItems(category.substring(5), bot));
                        }
                    }
                }
            }
        }
        return result;
    }

    static StatEffect itemEffect(int itemId) {
        Optional<StatEffect> cached = itemEffectCache.get(itemId);
        if (cached != null) {
            return cached.orElse(null);
        }
        try {
            StatEffect effect = ItemInformationProvider.getInstance().getItemEffect(itemId);
            itemEffectCache.putIfAbsent(itemId, Optional.ofNullable(effect));
            return effect;
        } catch (Exception e) {
            // Cache the failure too: getItemEffect parses WZ before throwing, so a single bad USE
            // item (re-scanned every potion check, no slot moves) otherwise re-paid that parse
            // forever - the ~300ms recurring potion-recovery-scan stall (Bowgurl @ Orbis).
            itemEffectCache.putIfAbsent(itemId, Optional.empty());
            return null;
        }
    }

    static boolean isRecoveryPotion(int itemId) {
        StatEffect fx = useEffect.effect(itemId);
        if (fx == null) return false;
        boolean heals = fx.getHp() > 0 || fx.getMp() > 0 || fx.getHpRate() > 0 || fx.getMpRate() > 0;
        return heals && fx.getStatups().isEmpty();
    }

    static boolean isBuffConsumable(int itemId) {
        StatEffect fx = useEffect.effect(itemId);
        return fx != null && !fx.getStatups().isEmpty();
    }

    private static void collectFromBag(Character bot, List<Item> result,
                                       InventoryType type, Predicate<Item> filter) {
        collectFromBag(bot, result, type, filter, false);
    }

    /** {@code botAwareSafety}: when true, a STALE quest item for this bot passes the safe-to-drop
     *  gate (Feature B) so it can be sold as clutter; the downstream {@code filter} (rare-drop,
     *  maker-material, sellPrice>0, ...) still decides. When false, ALL quest items are excluded
     *  (the strict EQUIP-safe behaviour). */
    private static void collectFromBag(Character bot, List<Item> result, InventoryType type,
                                       Predicate<Item> filter, boolean botAwareSafety) {
        Inventory inv = bot.getInventory(type);
        for (short slot = 1; slot <= inv.getSlotLimit(); slot++) {
            Item item = inv.getItem(slot);
            if (item == null) {
                continue;
            }
            boolean safe = botAwareSafety ? isSafeToDrop(bot, item) : isSafeToDrop(item);
            if (safe && filter.test(item)) result.add(item);
        }
    }

    static boolean hasItem(Character bot, Item item) {
        if (bot == null || item == null) {
            return false;
        }

        // A worn equip lives in the EQUIPPED inventory at a negative slot, but getInventoryType()
        // reports EQUIP (it's derived from the item id, not the slot). Resolve the real inventory by
        // slot sign so a currently-equipped item isn't falsely reported as gone — that false negative
        // broke the scroll-confirm re-check ("cant scroll that anymore") once the bot equipped the
        // proposed gear mid-grind, and the equivalent guard in startScrollReviewTrade.
        InventoryType type = item.getInventoryType();
        if (type == InventoryType.EQUIP && item.getPosition() < 0) {
            type = InventoryType.EQUIPPED;
        }
        Inventory inv = bot.getInventory(type);
        if (inv == null) {
            return false;
        }

        Item current = inv.getItem(item.getPosition());
        return current == item;
    }

    private static Character resolveTradeRecipient(BotEntry entry, Character bot) {
        // While an admin debug binding is fresh, command-driven trades open with the admin commander
        // instead of the bot's real owner so the admin can give/take items for debugging.
        Character owner = BotManager.getInstance().commanderOrOwner(entry);
        int recipientId = entry.pendingTradeRecipientId;
        if (recipientId <= 0) {
            return owner;
        }

        if (owner != null && owner.getId() == recipientId) {
            return owner;
        }

        if (bot.getMap() != null) {
            Character mapRecipient = bot.getMap().getCharacterById(recipientId);
            if (mapRecipient != null) {
                return mapRecipient;
            }
        }

        if (owner == null || owner.getParty() == null) {
            return null;
        }

        for (Character member : owner.getPartyMembersOnline()) {
            if (member != null && member.getId() == recipientId) {
                return member;
            }
        }

        return null;
    }

    // ─── Drop actions (floor) ─────────────────────────────────────────────────

    static void dropScrolls(BotEntry entry, Character bot) {
        int count = dropFromBag(bot, InventoryType.USE,
                item -> ItemConstants.isEquipScroll(item.getItemId()));
        reply(entry, bot, count, "scroll");
    }

    static void dropPotions(BotEntry entry, Character bot) {
        int count = dropFromBag(bot, InventoryType.USE,
                item -> isRecoveryPotion(item.getItemId()));
        reply(entry, bot, count, "potion");
    }

    static void dropEquips(BotEntry entry, Character bot) {
        int count = dropFromBag(bot, InventoryType.EQUIP, item -> true);
        BotManager.getInstance().botReply(entry,
                count > 0 ? "dropped " + count + " equip" + (count != 1 ? "s" : "") + "!"
                          : "equip bag is already empty");
    }

    static void dropTrashEquips(BotEntry entry, Character bot) {
        Set<Item> trash = new java.util.HashSet<>(collectTrashEquips(entry, bot));
        int count = dropFromBag(bot, InventoryType.EQUIP, trash::contains);
        BotManager.getInstance().botReply(entry,
                count > 0 ? "dropped " + count + " trash equip" + (count != 1 ? "s" : "") + "!"
                          : "no trash equips to drop");
    }

    static void dropBuffPots(BotEntry entry, Character bot) {
        int count = dropFromBag(bot, InventoryType.USE,
                item -> isBuffConsumable(item.getItemId()));
        reply(entry, bot, count, "buff pot");
    }

    static void dropEtc(BotEntry entry, Character bot) {
        int count = dropFromBag(bot, InventoryType.ETC, item -> true);
        reply(entry, bot, count, "etc item");
    }

    static void dropByName(BotEntry entry, Character bot, String nameFragment) {
        String normalizedFragment = normalizeItemQuery(nameFragment);
        int total = 0;
        for (InventoryType type : List.of(
                InventoryType.EQUIP, InventoryType.USE, InventoryType.ETC, InventoryType.SETUP)) {
            total += dropFromBag(bot, type, item -> {
                String name = normalizedItemName(item.getItemId());
                return name != null && name.contains(normalizedFragment);
            });
        }
        if (total <= 0) {
            BotManager.getInstance().botReply(entry, "couldn't find '" + nameFragment + "' in my bags");
        }
    }

    // ─── Inventory info ───────────────────────────────────────────────────────

    /** occupied/total for each bag: "equip: 10/24, use: 8/24, etc: 3/24, setup: 0/24" */
    static String slotsReport(Character bot) {
        StringBuilder sb = new StringBuilder();
        for (InventoryType type : List.of(
                InventoryType.EQUIP, InventoryType.USE, InventoryType.ETC, InventoryType.SETUP)) {
            Inventory inv = bot.getInventory(type);
            int used  = inv.getSlotLimit() - inv.getNumFreeSlot();
            int total = inv.getSlotLimit();
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(type.name().toLowerCase()).append(": ").append(used).append('/').append(total);
        }
        return sb.toString();
    }

    /** Full bag summary: "equip 10/24 | use 8/24 (3 scrolls, 5 pots, 2 buffs) | etc 3/24" */
    static String inventorySummary(Character bot) {
        StringBuilder sb = new StringBuilder();
        for (InventoryType type : List.of(
                InventoryType.EQUIP, InventoryType.USE, InventoryType.ETC, InventoryType.SETUP)) {
            Inventory inv = bot.getInventory(type);
            int used  = inv.getSlotLimit() - inv.getNumFreeSlot();
            int total = inv.getSlotLimit();
            if (!sb.isEmpty()) sb.append(" | ");
            sb.append(type.name().toLowerCase()).append(' ').append(used).append('/').append(total);
            if (type == InventoryType.USE) {
                int scrolls = 0, pots = 0, buffs = 0;
                for (Item item : inv.list()) {
                    if (!isSafeToDrop(item)) continue;
                    int id = item.getItemId();
                    if (ItemConstants.isEquipScroll(id)) scrolls += item.getQuantity();
                    else if (isRecoveryPotion(id))       pots    += item.getQuantity();
                    else if (isBuffConsumable(id))        buffs   += item.getQuantity();
                }
                if (scrolls > 0 || pots > 0 || buffs > 0) {
                    sb.append(" (");
                    boolean any = false;
                    if (scrolls > 0) { sb.append(scrolls).append(scrolls != 1 ? " scrolls" : " scroll"); any = true; }
                    if (pots > 0)    { if (any) sb.append(", "); sb.append(pots).append(pots != 1 ? " pots" : " pot"); any = true; }
                    if (buffs > 0)   { if (any) sb.append(", "); sb.append(buffs).append(buffs != 1 ? " buffs" : " buff"); }
                    sb.append(')');
                }
            }
        }
        return sb.toString();
    }

    // ─── Internals ────────────────────────────────────────────────────────────

    /** Orders equips like a plain inventory view: itemId first, then bag position. */
    private static List<Item> sortEquipsByItemId(List<Item> items) {
        if (items.size() <= 1) return items;
        List<Item> sorted = sortItemsByItemId(items);
        items.clear();
        items.addAll(sorted);
        return items;
    }

    /** Orders own reserved equips worst-to-best using the existing trade score helper. */
    private static List<Item> sortEquipsByTradeScore(List<Item> items, Character bot) {
        if (items.size() <= 1) return items;
        Job job = bot.getJob();
        items.sort(Comparator
                .comparingInt((Item item) -> item instanceof Equip equip ? equipTradeScore(equip, job) : Integer.MIN_VALUE)
                .thenComparingInt(Item::getItemId)
                .thenComparingInt(Item::getPosition));
        return items;
    }

    private enum EquipsGroup {
        NORMAL, RESERVED_FOR_OTHER, RESERVED_FOR_SELF;

        String categoryString() { return "equips:" + name().toLowerCase(); }

        static EquipsGroup fromCategory(String category) {
            if (category == null || !category.startsWith("equips:")) return null;
            try { return valueOf(category.substring("equips:".length()).toUpperCase()); }
            catch (IllegalArgumentException e) { return null; }
        }

        EquipsGroup next() {
            EquipsGroup[] vals = values();
            int next = ordinal() + 1;
            return next < vals.length ? vals[next] : null;
        }
    }

    private enum AmmoGroup {
        NON_OWN, OWN;

        String categoryString() { return "ammo:" + name().toLowerCase(); }

        static AmmoGroup fromCategory(String category) {
            if (category == null || !category.startsWith("ammo:")) return null;
            try { return valueOf(category.substring("ammo:".length()).toUpperCase()); }
            catch (IllegalArgumentException e) { return null; }
        }

        AmmoGroup next() {
            AmmoGroup[] vals = values();
            int next = ordinal() + 1;
            return next < vals.length ? vals[next] : null;
        }
    }

    private static List<Item> collectEquipsGroup(EquipsGroup group, BotEntry entry, Character bot) {
        return classifyEquipTradeGroups(entry, bot).itemsFor(group);
    }

    static String reservedEquipsCategory(int requestedPage) {
        return RESERVED_EQUIPS_CATEGORY_PREFIX + requestedPage;
    }

    static int clampTradePage(int requestedPage, int totalItems) {
        int maxPage = Math.max(1, (totalItems + TRADE_WINDOW_ITEM_LIMIT - 1) / TRADE_WINDOW_ITEM_LIMIT);
        return Math.max(1, Math.min(requestedPage, maxPage));
    }

    private static boolean isReservedEquipsCategory(String category) {
        return category != null && category.startsWith(RESERVED_EQUIPS_CATEGORY_PREFIX);
    }

    private static int requestedReservedEquipsPage(String category) {
        if (!isReservedEquipsCategory(category)) {
            return 1;
        }
        try {
            return Integer.parseInt(category.substring(RESERVED_EQUIPS_CATEGORY_PREFIX.length()));
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    private static List<Item> collectReservedEquips(EquipTradeGroups groups) {
        return new ArrayList<>(groups.reservedForSelf());
    }

    private static List<Item> collectReservedEquipTradePage(String category, BotEntry entry, Character bot) {
        EquipTradeGroups groups = classifyEquipTradeGroups(entry, bot);
        List<Item> reserved = collectReservedEquips(groups);
        if (reserved.isEmpty()) {
            return List.of();
        }
        int page = clampTradePage(requestedReservedEquipsPage(category), reserved.size());
        int from = (page - 1) * TRADE_WINDOW_ITEM_LIMIT;
        int to = Math.min(from + TRADE_WINDOW_ITEM_LIMIT, reserved.size());
        return new ArrayList<>(reserved.subList(from, to));
    }

    private static String reservedEquipsPageMessage(String category, BotEntry entry, Character bot) {
        EquipTradeGroups groups = classifyEquipTradeGroups(entry, bot);
        List<Item> reserved = collectReservedEquips(groups);
        if (reserved.isEmpty()) {
            return null;
        }
        int page = clampTradePage(requestedReservedEquipsPage(category), reserved.size());
        int lastPage = clampTradePage(Integer.MAX_VALUE, reserved.size());
        return "reserved equips page " + page + "/" + lastPage;
    }

    private static String equipsGroupMsg(String category) {
        EquipsGroup group = EquipsGroup.fromCategory(category);
        if (group == null) return null;
        return switch (group) {
            case RESERVED_FOR_OTHER -> BotManager.randomReply(TRADE_RESERVED_FOR_OTHER_MSGS);
            case RESERVED_FOR_SELF  -> BotManager.randomReply(TRADE_RESERVED_FOR_SELF_MSGS);
            default -> null;
        };
    }

    private static String nextEquipsGroup(String category, BotEntry entry, Character bot) {
        EquipsGroup current = EquipsGroup.fromCategory(category);
        if (current == null) return null;
        EquipTradeGroups groups = classifyEquipTradeGroups(entry, bot);
        for (EquipsGroup g = current.next(); g != null; g = g.next()) {
            if (!groups.itemsFor(g).isEmpty()) return g.categoryString();
        }
        return null;
    }

    private static String nextAmmoGroup(String category, Character bot) {
        AmmoGroup current = AmmoGroup.fromCategory(category);
        if (current == null) return null;
        AmmoTradeGroups groups = classifyAmmoTradeGroups(bot);
        for (AmmoGroup group = current.next(); group != null; group = group.next()) {
            if (!groups.itemsFor(group).isEmpty()) return group.categoryString();
        }
        return null;
    }

    private static void startEquipsGroupTradeTransfer(Character owner, BotEntry entry, Character bot) {
        EquipTradeGroups groups = classifyEquipTradeGroups(entry, bot);
        for (EquipsGroup group : EquipsGroup.values()) {
            List<Item> items = groups.itemsFor(group);
            if (!items.isEmpty()) {
                String category = group.categoryString();
                startTradeSequence(category, owner, items, 0, false, entry, bot);
                String msg = equipsGroupMsg(category);
                if (msg != null) entry.pendingTradeCategoryMsg = msg;
                return;
            }
        }
        BotManager.getInstance().botReply(entry, noItemsReply("equips"));
    }

    private static void startAmmoGroupTradeTransfer(Character owner, BotEntry entry, Character bot) {
        AmmoTradeGroups groups = classifyAmmoTradeGroups(bot);
        for (AmmoGroup group : AmmoGroup.values()) {
            List<Item> items = groups.itemsFor(group);
            if (!items.isEmpty()) {
                startTradeSequence(group.categoryString(), owner, items, 0, false, entry, bot);
                return;
            }
        }
        BotManager.getInstance().botReply(entry, noItemsReply("ammo"));
    }

    private static UseTradeGroups classifyUseTradeGroups(Character bot, Character recipient) {
        List<Item> uncategorized = new ArrayList<>();
        List<Item> categorizedOther = new ArrayList<>();
        List<Item> potionAmmo = new ArrayList<>();
        collectFromBag(bot, uncategorized, InventoryType.USE, item -> {
            int id = item.getItemId();
            if (isRecoveryPotion(id) || isTradeAmmoItem(id)) {
                potionAmmo.add(item);
                return false;
            }
            if (ItemConstants.isEquipScroll(id) || isBuffConsumable(id)) {
                categorizedOther.add(item);
                return false;
            }
            return true;
        });
        List<Item> ordered = prioritizeTradeUseItems(uncategorized, categorizedOther, potionAmmo, recipient);
        int uncategorizedCount = uncategorized.size();
        return new UseTradeGroups(
                new ArrayList<>(ordered.subList(0, uncategorizedCount)),
                new ArrayList<>(ordered.subList(uncategorizedCount, ordered.size())));
    }

    private static AmmoTradeGroups classifyAmmoTradeGroups(Character bot) {
        List<Item> nonOwn = new ArrayList<>();
        List<Item> own = new ArrayList<>();
        WeaponType ownAmmoWeaponType = tradeAmmoWeaponType(bot);
        collectFromBag(bot, nonOwn, InventoryType.USE, item -> {
            WeaponType ammoType = ammoWeaponType(item.getItemId());
            if (ammoType == null) {
                return false;
            }
            if (ammoType == ownAmmoWeaponType) {
                own.add(item);
                return false;
            }
            return true;
        });
        nonOwn.sort(Comparator.comparingInt(Item::getItemId));
        own.sort(Comparator
                .comparingInt((Item item) -> ItemInformationProvider.getInstance().getWatkForProjectile(item.getItemId()))
                .thenComparingInt(Item::getItemId));
        return new AmmoTradeGroups(nonOwn, own);
    }

    private static List<Item> collectTrashEquips(BotEntry entry, Character bot) {
        return collectEquipsGroup(EquipsGroup.NORMAL, entry, bot);
    }

    static List<Item> collectSellTrashEquips(BotEntry entry, Character bot) {
        List<Item> trash = collectTrashEquips(entry, bot);
        if (trash.isEmpty()) {
            return trash;
        }

        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        List<Item> result = new ArrayList<>(trash.size());
        List<Equip> keptValuables = new ArrayList<>();
        for (Item item : trash) {
            if (item instanceof Equip equip) {
                if (shouldKeepForSellTrash(ii, equip)) {
                    keptValuables.add(equip);
                } else {
                    result.add(item);
                }
            }
        }
        result.addAll(valuableEquipOverflow(ii, keptValuables));
        return result;
    }

    // The valuables shelf is bounded: good-roll equips kept for future trading are ranked by
    // trade value and only the best KEEP_VALUABLE_EQUIP_SLOTS stay; the overflow sells like
    // any junk. Without the cap every above-base roll accumulates forever.
    static final int KEEP_VALUABLE_EQUIP_SLOTS = 24;

    // Absolute keep gate ON TOP of the bounded shelf: an equip this far above its clean base
    // (one good att-scroll pass, or +25 raw stat points) is never auto-sold even when the
    // shelf overflows — a mule spawned holding a bag of genuine valuables must not liquidate
    // them just because there are more than the shelf holds. Bag pressure is the lesser evil.
    static final double NEVER_SELL_TRADE_SCORE = 25.0;

    /** Kept-for-value equips ranked by trade value descending (rank = index + 1). Shared by
     *  the real sell pipeline ({@link #valuableEquipOverflow}) and the debug classifier
     *  ({@link #classifyBagEquips}) so both always agree. */
    static List<Equip> rankKeptValuables(ItemInformationProvider ii, List<Equip> kept) {
        List<Equip> ranked = new ArrayList<>(kept);
        ranked.sort(Comparator.comparingDouble((Equip e) -> tradeValueScore(ii, e)).reversed()
                .thenComparingInt(Item::getItemId));
        return ranked;
    }

    /** Kept-for-value equips beyond the shelf cap, weakest trade value first — except equips
     *  at or above {@link #NEVER_SELL_TRADE_SCORE}, which never sell regardless of overflow. */
    static List<Item> valuableEquipOverflow(ItemInformationProvider ii, List<Equip> kept) {
        if (kept.size() <= KEEP_VALUABLE_EQUIP_SLOTS) {
            return List.of();
        }
        List<Equip> ranked = rankKeptValuables(ii, kept);
        List<Item> overflow = new ArrayList<>();
        for (Equip e : ranked.subList(KEEP_VALUABLE_EQUIP_SLOTS, ranked.size())) {
            if (tradeValueScore(ii, e) < NEVER_SELL_TRADE_SCORE) {
                overflow.add(e);
            }
        }
        return overflow;
    }

    /**
     * Bot-agnostic trade value of a kept roll: how far it beats its clean WZ base. Watk weighs
     * 5 (what buyers pay for); matk is worth a stat point like everywhere else in the offense
     * SSOT (user-tuned: +1 INT ≈ +1 MATK). Null ii (tests) scores raw values against base 0.
     */
    static double tradeValueScore(ItemInformationProvider ii, Equip equip) {
        Map<String, Integer> stats = ii != null ? ii.getEquipStats(equip.getItemId()) : null;
        double score = 0.0;
        score += 5.0 * aboveBase(equip.getWatk(), stats, "PAD");
        score += aboveBase(equip.getMatk(), stats, "MAD");
        score += aboveBase(equip.getStr(), stats, "STR");
        score += aboveBase(equip.getDex(), stats, "DEX");
        score += aboveBase(equip.getInt(), stats, "INT");
        score += aboveBase(equip.getLuk(), stats, "LUK");
        return score;
    }

    private static int aboveBase(int value, Map<String, Integer> stats, String key) {
        int base = stats != null ? stats.getOrDefault(key, 0) : 0;
        return Math.max(0, value - base);
    }

    // ETC items consumed by skills (itemCon) — never trash, even though NPCs pay for them.
    private static final Set<Integer> SKILL_CONSUMED_ETC = Set.of(
            ItemId.MAGIC_ROCK, 4006001 /* Summoning Rock */);

    // "Rare drop" keep gate: an item whose BEST dropper hands it out at most this often (out of
    // 1,000,000 kills, i.e. <=1%) is worth far more to a future buyer than any NPC pays — a human
    // wouldn't NPC it. drop_data check: this keeps ~75 of ~870 droppable ETC items; common mob
    // drops (typically >=10%) all sell. Equips are NOT gated by rarity: most droppable equips are
    // <=1% yet clean average rolls are NPC fodder — good rolls are already stat-protected
    // (shouldKeepForSellTrash) and self-useful gear is reserved (collectPotentialSelfUpgradeItems).
    private static final int RARE_DROP_KEEP_CHANCE = 10_000;
    private static final int MONSTER_CRYSTAL_LEFTOVER_KEEP_QUANTITY = 100;

    // === USE value model tunables (pressure-driven + value shelf) ===
    // A USE stack worth at least this much is a trade good and is never auto-sold, even when the
    // bag is cramped (analog of NEVER_SELL_TRADE_SCORE for equips).
    static int USE_NEVER_SELL_MESO = 50_000;
    // All-cure (status insurance) reserve kept in the combat runway.
    static int ALL_CURE_RESERVE_SLOTS = 1;

    // Test seams: ItemInformationProvider's WZ/DB static initializer can't run in unit tests
    // (same pattern as BotShopManager) — price/leftover/rarity/maker lookups go through these.
    @FunctionalInterface
    interface SellPriceLookup {
        int price(int itemId, int quantity);
    }
    static SellPriceLookup sellPrice =
            (id, qty) -> ItemInformationProvider.getInstance().getPrice(id, qty);
    // Rechargeable ammo (throwing stars/bullets) value is quantity-INDEPENDENT: one set is a set
    // (0 == 9999). Its per-set worth is the base whole price, not getPrice(id, qty). Used so a
    // duplicate slot of an already-kept star tier scores ~nothing on the value shelf.
    static IntUnaryOperator ammoSetValue =
            id -> ItemInformationProvider.getInstance().getWholePrice(id);
    // Projectile attack, for picking a bot's best ammo tier.
    static IntUnaryOperator projectileWatk =
            id -> ItemInformationProvider.getInstance().getWatkForProjectile(id);
    // A USE item effect, cached, for the recovery/cure/buff category predicates.
    @FunctionalInterface
    interface ItemEffectLookup {
        StatEffect effect(int itemId);
    }
    static ItemEffectLookup useEffect = BotInventoryManager::itemEffect;
    @FunctionalInterface
    interface ScrollStatsLookup {
        Map<String, Integer> stats(int itemId);
    }
    static ScrollStatsLookup scrollStats =
            id -> ItemInformationProvider.getInstance().getEquipStats(id);
    @FunctionalInterface
    interface ScrollMarketValueLookup {
        double value(Character bot, int itemId);
    }
    static ScrollMarketValueLookup scrollMarketValue =
            BotScrollManager::scrollMarketValueMeso;
    static IntUnaryOperator makerCrystalFromLeftover =
            id -> ItemInformationProvider.getInstance().getMakerCrystalFromLeftover(id);
    static IntUnaryOperator bestDropChance = BotScrollManager::bestDropChance;
    // Maker skill level of the bot; seam so the crystal-leftover keep gate (a leftover is only worth
    // hoarding when the bot can actually convert it) stays testable without WZ/skill data.
    static java.util.function.ToIntFunction<Character> makerSkillLevel =
            client.processor.action.MakerProcessor::getMakerSkillLevel;

    private static boolean isRareDrop(int itemId) {
        int chance = bestDropChance.applyAsInt(itemId);
        return chance > 0 && chance <= RARE_DROP_KEEP_CHANCE;
    }

    // Omok pieces (4030000-4030016) and assembled Omok sets (4080000-4080011): minigame clutter the
    // bot will never use, always sold even when they'd otherwise read as rare drops / crystal
    // leftovers (this whitelist forces the sale, overriding those keeps).
    private static boolean isOmokItem(int itemId) {
        return isInRange(itemId, 4030000, 4030016) || isInRange(itemId, 4080000, 4080011);
    }

    // A monster-crystal leftover is only worth keeping if the bot can actually convert it: the Maker
    // leftover->crystal recipe (MakerItemFactory.generateLeftoverCrystalEntry) requires Maker skill
    // level >= 1. Without Maker, 100-stacks of leftovers are pure clutter and should be sold.
    private static boolean canMakeMonsterCrystals(Character bot) {
        return bot != null && makerSkillLevel.applyAsInt(bot) >= 1;
    }

    // A crystal-leftover stack the bot should keep: convertible to a Maker monster crystal, big
    // enough for the 100-count recipe, and the bot has the Maker skill to do it.
    private static boolean keepCrystalLeftover(Character bot, Item item) {
        return canMakeMonsterCrystals(bot)
                && makerCrystalFromLeftover.applyAsInt(item.getItemId()) != -1
                && item.getQuantity() >= MONSTER_CRYSTAL_LEFTOVER_KEEP_QUANTITY;
    }

    private static boolean isMakerMaterial(int itemId) {
        return ItemConstants.isMakerReagent(itemId)
                || isInRange(itemId, 4004000, 4004004) // stat crystal ores
                || isInRange(itemId, 4005000, 4005004) // stat crystals
                || isInRange(itemId, 4007000, 4007007) // magic powders
                || isInRange(itemId, 4010000, 4010007) // ores
                || isInRange(itemId, 4011000, 4011008) // plates, Moon Rock, Lidium
                || isInRange(itemId, 4020000, 4020009) // jewel ores, Piece of Time
                || isInRange(itemId, 4021000, 4021009) // jewels, Star Rock
                || itemId / 10000 == 413 // stimulators and crafting manuals
                || itemId / 10000 == 426; // monster crystals
    }

    private static boolean isInRange(int itemId, int first, int last) {
        return itemId >= first && itemId <= last;
    }

    static short sellTrashQuantity(Item item) {
        if (item == null || item.getQuantity() <= 0) {
            return 0;
        }
        // Whole slot: a selected stack (including a redundant rechargeable ammo set, whose value is
        // quantity-independent) is shed entirely. Ammo reserves are now decided by the runway/shelf
        // model, not a hardcoded per-item quantity guard.
        return item.getQuantity();
    }

    // === USE consumable value model =========================================================
    // Pressure-driven + value shelf (mirrors the equip kept-valuables shelf). Every USE stack is:
    //   RUNWAY - combat necessity, never auto-sold: best ammo set, a recovery runway sized to the
    //            bot, a small all-cure reserve.
    //   JUNK   - zero trade value, always sellable: single-ailment cures (antidote/eyedrop/...),
    //            equip scrolls this job never values, stale quest clutter.
    //   SHELF  - everything else (extra recovery, other-class & surplus ammo, buffs, misc). Kept
    //            for trade/use UNLESS the bag is cramped, then the lowest value-per-slot stacks
    //            sell first. Stacks worth >= USE_NEVER_SELL_MESO are protected even under pressure.
    // Ammo and rarer consumables carry real trade value, so they are NEVER quantity-capped; only
    // genuine low-value overflow sells, and only under bag pressure.
    enum UseTier { RUNWAY, JUNK, SHELF }

    /** keepValue: value-per-slot used to rank the shelf (ascending = sold first). shelfRank is the
     *  1-based ascending position (1 = sold first). reason is a coarse label for the debug dump. */
    record UseClass(UseTier tier, double keepValue, int shelfRank, String reason) {}

    private static boolean isAllCurePotion(int itemId) {
        StatEffect fx = useEffect.effect(itemId);
        return fx != null && fx.curesAllAbnormalStatus();
    }

    // Single-ailment cures (antidote=poison, eyedrop=darkness, tonic, holy water): worthless, always
    // sell. An all-cure potion is reserved instead (status insurance).
    private static boolean isSingleCurePotion(int itemId) {
        StatEffect fx = useEffect.effect(itemId);
        return fx != null && fx.curesAnyDebuff() && !fx.curesAllAbnormalStatus();
    }

    private static boolean isUseJunk(Character bot, int itemId) {
        return isSingleCurePotion(itemId)
                || (ItemConstants.isEquipScroll(itemId) && isIrrelevantEquipScroll(bot, itemId))
                || isStaleQuestItem(bot, itemId);
    }

    private static String junkReason(Character bot, int itemId) {
        if (isSingleCurePotion(itemId)) return "cure-junk";
        if (ItemConstants.isEquipScroll(itemId)) return "scroll-junk";
        return "quest-stale";
    }

    // Recovery potions ranked for the runway: how much they actually restore (flat + a modest
    // weight on percent-recovery), so the bot keeps its strongest sustain and sheds weak low-tier
    // pots under pressure.
    private static double recoveryHealScore(int itemId) {
        StatEffect fx = useEffect.effect(itemId);
        if (fx == null) return 0;
        return fx.getHp() + fx.getMp() + (fx.getHpRate() + fx.getMpRate()) * 10.0;
    }

    /** SSOT classification of the USE bag into RUNWAY/JUNK/SHELF, with shelf value ranking. Shared
     *  by the sell collectors and the {@code inv debug} dump so both always agree (no second
     *  decision tree). */
    static Map<Item, UseClass> classifyBagUse(Character bot) {
        WeaponType ownAmmoType = tradeAmmoWeaponType(bot);
        List<Item> all = new ArrayList<>();
        // botAwareSafety: stale quest items pass the quest exclusion so they can be classified JUNK.
        collectFromBag(bot, all, InventoryType.USE, item -> true, true);

        Map<Item, UseClass> out = new IdentityHashMap<>();
        List<Item> recovery = new ArrayList<>();
        List<Item> allCure = new ArrayList<>();
        List<Item> ownAmmo = new ArrayList<>();
        List<Item> buffs = new ArrayList<>();
        List<Item> shelf = new ArrayList<>(); // other-class/surplus ammo, misc -> kept unless cramped

        for (Item item : all) {
            int id = item.getItemId();
            if (isUseJunk(bot, id)) {
                out.put(item, new UseClass(UseTier.JUNK, 0, 0, junkReason(bot, id)));
                continue;
            }
            WeaponType ammoType = ammoWeaponType(id);
            if (ammoType != null && ammoType == ownAmmoType) {
                ownAmmo.add(item);
            } else if (isRecoveryPotion(id)) {
                recovery.add(item);
            } else if (isAllCurePotion(id)) {
                allCure.add(item);
            } else if (isBuffConsumable(id)) {
                buffs.add(item);
            } else {
                shelf.add(item); // other-class ammo, uncategorized -> kept unless cramped
            }
        }

        classifyRecoveryRunway(recovery, out, shelf);

        // RUNWAY: a little all-cure insurance; surplus to the shelf. (Not resupplied -> no buy loop.)
        allCure.sort(Comparator.comparingInt(Item::getQuantity).reversed());
        for (int i = 0; i < allCure.size(); i++) {
            if (i < ALL_CURE_RESERVE_SLOTS) out.put(allCure.get(i), new UseClass(UseTier.RUNWAY, 0, 0, "allcure-reserve"));
            else shelf.add(allCure.get(i));
        }

        classifyOwnAmmoRunway(ownAmmo, out, shelf);
        classifyBuffRunway(buffs, bot, out, shelf);
        rankUseShelf(bot, shelf, out);
        return out;
    }

    // Recovery runway sized to the RESUPPLY target so a cramped trip never sheds pots the bot would
    // immediately rebuy (buy/sell loop). Keep strongest-heal stacks first until both the HP and MP
    // recovery quantities cover BotShopManager.potResupplyTarget(); the rest is surplus -> shelf.
    private static void classifyRecoveryRunway(List<Item> recovery, Map<Item, UseClass> out, List<Item> shelf) {
        recovery.sort(Comparator
                .comparingDouble((Item it) -> recoveryHealScore(it.getItemId())).reversed()
                .thenComparing(Comparator.comparingInt(Item::getQuantity).reversed()));
        int target = BotShopManager.potResupplyTarget();
        int hp = 0;
        int mp = 0;
        for (Item it : recovery) {
            StatEffect fx = useEffect.effect(it.getItemId());
            boolean keepForHp = hp < target && BotPotionManager.healsHp(fx);
            boolean keepForMp = mp < target && BotPotionManager.healsMp(fx);
            if (keepForHp || keepForMp) {
                out.put(it, new UseClass(UseTier.RUNWAY, 0, 0, "recovery-runway"));
                if (BotPotionManager.healsHp(fx)) hp += it.getQuantity();
                if (BotPotionManager.healsMp(fx)) mp += it.getQuantity();
            } else {
                shelf.add(it);
            }
        }
    }

    // Own ammo runway: rechargeable (stars/bullets) needs only ONE set of the best tier (it
    // recharges free at shops, so quantity is irrelevant); consumed ammo (arrows/bolts) keeps the
    // best tier up to the RESUPPLY target quantity (so it never auto-sells what it would rebuy).
    // Every other own-ammo slot — lesser tiers and duplicate sets — drops to the shelf.
    private static void classifyOwnAmmoRunway(List<Item> ownAmmo, Map<Item, UseClass> out, List<Item> shelf) {
        if (ownAmmo.isEmpty()) {
            return;
        }
        ownAmmo.sort(Comparator
                .comparingInt((Item it) -> projectileWatk.applyAsInt(it.getItemId())).reversed()
                .thenComparing(Comparator.comparingInt(Item::getQuantity).reversed()));
        int bestTier = ownAmmo.get(0).getItemId();
        boolean rechargeable = ItemConstants.isRechargeable(bestTier);
        int target = rechargeable ? 1 : BotShopManager.ammoResupplyTarget();
        int kept = 0;
        for (Item it : ownAmmo) {
            if (it.getItemId() == bestTier && kept < target) {
                out.put(it, new UseClass(UseTier.RUNWAY, 0, 0, "ammo-runway"));
                kept += rechargeable ? 1 : it.getQuantity();
            } else {
                shelf.add(it);
            }
        }
    }

    // Sort own ammo slots strongest-first so RangedAttackHandler's first-slot-wins pick always
    // uses the best available tier. Rechargeable stacks are left as-is (1 stack = 1 full set).
    static void sortOwnAmmoSlots(Character bot) {
        WeaponType ownType = tradeAmmoWeaponType(bot);
        if (ownType == null) return;

        Inventory use = bot.getInventory(InventoryType.USE);
        List<Item> ammo = new ArrayList<>();

        use.lockInventory();
        try {
            for (short i = 1; i <= use.getSlotLimit(); i++) {
                Item item = use.getItem(i);
                if (item != null && ammoWeaponType(item.getItemId()) == ownType) {
                    ammo.add(item);
                }
            }
            if (ammo.size() <= 1) return;
            for (Item item : ammo) use.removeSlot(item.getPosition());
            ammo.sort(Comparator.comparingInt((Item it) -> projectileWatk.applyAsInt(it.getItemId())).reversed());
            for (Item item : ammo) use.addItem(item);
        } finally {
            use.unlockInventory();
        }
    }

    // Buff runway: the buff pots this bot actually uses (best per relevant stat-key, WATK/MATK
    // included) per BotBuffManager's SSOT selection; inferior/duplicate buff stacks shelf.
    private static void classifyBuffRunway(List<Item> buffs, Character bot,
                                           Map<Item, UseClass> out, List<Item> shelf) {
        if (buffs.isEmpty()) {
            return;
        }
        java.util.Set<Item> runway = BotBuffManager.runwayBuffItems(bot);
        for (Item it : buffs) {
            if (runway.contains(it)) {
                out.put(it, new UseClass(UseTier.RUNWAY, 0, 0, "buff-runway"));
            } else {
                shelf.add(it);
            }
        }
    }

    // Rank the shelf by value-per-slot ascending (sold first). Rechargeable ammo is valued per-set
    // (quantity-independent): the first slot of each tier carries the set value, every further slot
    // of that same tier is a redundant duplicate worth ~0 and leads the sale.
    private static void rankUseShelf(Character bot, List<Item> shelf, Map<Item, UseClass> out) {
        java.util.Set<Integer> seenRechargeableTier = new java.util.HashSet<>();
        // A rechargeable tier already kept in the runway is "seen", so its first shelf slot is a
        // redundant duplicate.
        for (var e : out.entrySet()) {
            int id = e.getKey().getItemId();
            if (e.getValue().tier() == UseTier.RUNWAY
                    && ammoWeaponType(id) != null && ItemConstants.isRechargeable(id)) {
                seenRechargeableTier.add(id);
            }
        }
        // Fuller stacks first, so the slot that keeps a tier's set value is the fullest one.
        List<Item> ordered = new ArrayList<>(shelf);
        ordered.sort(Comparator.comparingInt(Item::getQuantity).reversed());
        Map<Item, Double> value = new IdentityHashMap<>();
        for (Item it : ordered) {
            int id = it.getItemId();
            double v;
            if (ammoWeaponType(id) != null && ItemConstants.isRechargeable(id)) {
                v = seenRechargeableTier.add(id) ? ammoSetValue.applyAsInt(id) : 0;
            } else if (ItemConstants.isEquipScroll(id)) {
                v = Math.max(sellPrice.price(id, it.getQuantity()),
                        scrollMarketValue.value(bot, id) * it.getQuantity());
            } else {
                v = sellPrice.price(id, it.getQuantity());
            }
            value.put(it, Math.max(0, v));
        }
        List<Item> ranked = new ArrayList<>(shelf);
        ranked.sort(Comparator.comparingDouble(value::get));
        for (int i = 0; i < ranked.size(); i++) {
            Item it = ranked.get(i);
            out.put(it, new UseClass(UseTier.SHELF, value.get(it), i + 1, shelfReason(it, value.get(it))));
        }
    }

    private static String shelfReason(Item item, double value) {
        int id = item.getItemId();
        if (ammoWeaponType(id) != null) {
            return value <= 0 ? "ammo-dup-set" : "ammo-shelf";
        }
        if (ItemConstants.isEquipScroll(id)) return "scroll";
        if (isBuffConsumable(id)) return "buff";
        if (isRecoveryPotion(id)) return "recovery-extra";
        if (isAllCurePotion(id)) return "allcure-extra";
        return "misc";
    }

    // Always-sellable JUNK only: single cures, irrelevant scrolls, stale quest clutter an NPC pays
    // for. Trade-worthy stacks (ammo, pots, buffs) are NOT here — they sell only under bag pressure
    // via collectCrampedUseSales. Keeps a plain "sell trash" trip non-destructive.
    static List<Item> collectSellTrashUseItems(Character bot) {
        List<Item> result = new ArrayList<>();
        for (var e : classifyBagUse(bot).entrySet()) {
            Item item = e.getKey();
            if (e.getValue().tier() == UseTier.JUNK
                    && item.getQuantity() > 0
                    && sellPrice.price(item.getItemId(), item.getQuantity()) > 0) {
                result.add(item);
            }
        }
        return result;
    }

    // Under bag pressure: the lowest value-per-slot SHELF stacks (worst slot first), enough to free
    // `slotsToFree` slots. Excludes the runway, the JUNK already in the sell plan, and anything at
    // or above USE_NEVER_SELL_MESO (trade goods stay). Small cheap stacks and redundant ammo sets
    // lead; the user's example holds: a 3x500 (1500) stack sells before a 700x10 (7000) one.
    static List<Item> collectCrampedUseSales(Character bot, int slotsToFree, java.util.Set<Item> exclude) {
        if (slotsToFree <= 0) {
            return List.of();
        }
        List<Map.Entry<Item, UseClass>> shelf = new ArrayList<>();
        for (var e : classifyBagUse(bot).entrySet()) {
            Item item = e.getKey();
            if (e.getValue().tier() != UseTier.SHELF) continue;
            if (exclude != null && exclude.contains(item)) continue;
            if (e.getValue().keepValue() >= USE_NEVER_SELL_MESO) continue;
            if (item.getQuantity() <= 0) continue;
            if (sellPrice.price(item.getItemId(), item.getQuantity()) <= 0) continue;
            shelf.add(e);
        }
        shelf.sort(Comparator.comparingDouble(en -> en.getValue().keepValue()));
        List<Item> result = new ArrayList<>();
        for (var en : shelf) {
            if (result.size() >= slotsToFree) break;
            result.add(en.getKey());
        }
        return result;
    }

    /** True when the bag holds at least one shelf stack that a cramped sell trip could unload. */
    static boolean crampedUseSalesAvailable(Character bot) {
        return !collectCrampedUseSales(bot, 1, null).isEmpty();
    }

    // An equip scroll is sell-trash only when its effect grants nothing of trade value:
    // main stats (STR/DEX/INT/LUK), att/matt and speed/jump are kept for EVERY job (user: a
    // warrior keeps INT scrolls - stat scrolls are prime trade goods); only acc is judged
    // against the bot's own job. What's left to sell: pure hp/mp/def/avoid (and acc-only for
    // classes that never value acc). Deliberately NOT gated by isRareDrop: nearly every
    // scroll is a rare drop, the gate would nullify this. Meta scrolls (clean slate/chaos/
    // modifier) grant no inc stats but have special effects, so they stay. No collision with
    // BotScrollManager's planner: it only queues scrolls with positive offense gain.
    private static final List<String> UNIVERSALLY_KEPT_SCROLL_STAT_KEYS =
            List.of("STR", "DEX", "INT", "LUK", "PAD", "MAD", "Speed", "Jump");

    private static boolean isIrrelevantEquipScroll(Character bot, int itemId) {
        if (!ItemConstants.isEquipScroll(itemId)) {
            return false;
        }
        if (ItemConstants.isCleanSlate(itemId) || ItemConstants.isChaosScroll(itemId)
                || ItemConstants.isModifierScroll(itemId)) {
            return false;
        }
        Map<String, Integer> stats = scrollStats.stats(itemId);
        if (stats == null) {
            return false; // unknown effect: keep
        }
        for (String key : UNIVERSALLY_KEPT_SCROLL_STAT_KEYS) {
            if (stats.getOrDefault(key, 0) > 0) {
                return false;
            }
        }
        for (BotEquipManager.RelevantStat stat : BotEquipManager.relevantStatsFor(bot.getJob())) {
            if (stats.getOrDefault(scrollStatKey(stat), 0) > 0) {
                return false;
            }
        }
        return true;
    }

    // Scroll effects come from the same WZ read path as equips (getEquipStats: "inc"-stripped
    // keys), so WATK/MATK live under PAD/MAD.
    static String scrollStatKey(BotEquipManager.RelevantStat stat) {
        return switch (stat) {
            case STR -> "STR";
            case DEX -> "DEX";
            case INT -> "INT";
            case LUK -> "LUK";
            case WATK -> "PAD";
            case MATK -> "MAD";
            case ACC -> "ACC";
        };
    }

    // Trash ETC = anything not on a keep whitelist. Quest items/untradeables are already excluded by
    // collectFromBag (isSafeToDrop). Kept: omok collectibles, skill-consumed rocks, Maker/crafting
    // materials, rare drops, and (only if the bot has the Maker skill) crystal leftovers convertible
    // to monster crystals. Everything else is sold even at 0 NPC price - 0-value clutter (e.g. Pig
    // Vein) only leaves the bag if it's collected here.
    static List<Item> collectSellTrashEtcItems(Character bot) {
        List<Item> result = new ArrayList<>();
        // botAwareSafety: stale ETC quest items pass the quest-item exclusion; the keeps below still
        // protect anything with genuine value, so only true clutter is collected.
        collectFromBag(bot, result, InventoryType.ETC, item -> {
            int id = item.getItemId();
            if (isOmokItem(id)) {
                return true; // omok clutter: always sold, overrides the rare-drop/leftover keeps below
            }
            if (SKILL_CONSUMED_ETC.contains(id) || isMakerMaterial(id) || isRareDrop(id)
                    || keepCrystalLeftover(bot, item)) {
                return false;
            }
            return true;
        }, true);
        return result;
    }

    /** Drop disposable quest items the sell pipeline can't clear - untradeable ones a shop refuses -
     *  the same way a player ditches dead quest junk: {@link InventoryManipulator#drop}, which makes a
     *  quest item vanish on the ground (a disappearing drop). "Disposable" = {@link #isStaleQuestItem}
     *  (every using-quest is completed, severely outleveled, or proven unfinishable). Tradeable
     *  disposables are left for the shop sell trip, which recovers their NPC value. No-op when the bot
     *  holds none, so it's cheap to attempt on the autopilot hygiene tick. */
    static void discardDisposableQuestItems(Character bot) {
        if (bot == null || bot.getClient() == null) {
            return;
        }
        for (InventoryType type : List.of(InventoryType.EQUIP, InventoryType.USE, InventoryType.ETC)) {
            Inventory inv = bot.getInventory(type);
            if (inv == null) {
                continue;
            }
            // Snapshot the slots first: drop() mutates the inventory we'd be iterating.
            List<Short> slots = new ArrayList<>();
            for (short slot = 1; slot <= inv.getSlotLimit(); slot++) {
                Item it = inv.getItem(slot);
                if (it != null && shouldDiscardQuestItem(bot, it)) {
                    slots.add(slot);
                }
            }
            for (short slot : slots) {
                Item it = inv.getItem(slot);
                if (it != null && shouldDiscardQuestItem(bot, it)) {
                    InventoryManipulator.drop(bot.getClient(), type, slot, it.getQuantity());
                }
            }
        }
    }

    /** A disposable quest item the shop can't buy (untradeable under the server config) - dropping is
     *  the only way to free its slot. {@code !isSafeToDrop} is exactly the unsellable case: a stale
     *  quest item that the untradeable gate still blocks from the sell pipeline. */
    private static boolean shouldDiscardQuestItem(Character bot, Item item) {
        int id = item.getItemId();
        return questItem.test(id) && isStaleQuestItem(bot, id) && !isSafeToDrop(bot, item);
    }

    // Everything a "sell trash" shop visit should unload: trash equips + trash USE + trash ETC.
    static List<Item> collectSellTrashItems(BotEntry entry, Character bot) {
        List<Item> result = new ArrayList<>(collectSellTrashEquips(entry, bot));
        result.addAll(collectSellTrashUseItems(bot));
        result.addAll(collectSellTrashEtcItems(bot));
        // The "farm <item>" objective is the whole point of the trip — never sell it.
        // entry == null = @autosell debug preview on a real player's character (no bot state).
        if (entry != null && entry.autopilotFarmItemId != 0) {
            result.removeIf(item -> item.getItemId() == entry.autopilotFarmItemId);
        }
        return result;
    }

    // @autosell (admin debug): run the UNCHANGED bot sell-trash pipeline against a real
    // player's character. Preview lists what would sell, grouped by inventory type; confirm
    // sells everything instantly at NPC prices — same removeFromSlot + gainMeso effect as
    // Shop.sell, minus the shop session and humanlike step delays (debug tool, not bot play).
    static List<String> autoSellPreviewLines(Character chr) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        List<Item> items = collectSellTrashItems(null, chr);
        if (items.isEmpty()) {
            return List.of("autosell: nothing the bot pipeline would sell");
        }
        List<String> lines = new ArrayList<>();
        for (InventoryType type : List.of(InventoryType.EQUIP, InventoryType.USE, InventoryType.ETC)) {
            List<String> descs = items.stream()
                    .filter(item -> item.getInventoryType() == type)
                    .map(item -> describeAutoSellItem(ii, chr, item))
                    .toList();
            if (!descs.isEmpty()) {
                appendWrappedListLines(lines, autoSellTypeLabel(type) + ": ", descs);
            }
        }
        lines.add("autosell: " + items.size() + " item" + (items.size() != 1 ? "s" : "")
                + " - @autosell confirm sells them now");
        return lines;
    }

    static List<String> autoSellExecute(Client c) {
        Character chr = c.getPlayer();
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        List<Item> items = collectSellTrashItems(null, chr);
        int sold = 0;
        long mesoGained = 0;
        for (Item item : items) {
            if (!hasItem(chr, item)) {
                continue;
            }
            short quantity = sellTrashQuantity(item);
            if (quantity <= 0) {
                continue;
            }
            InventoryManipulator.removeFromSlot(c, item.getInventoryType(), (byte) item.getPosition(),
                    quantity, false);
            int price = ii.getPrice(item.getItemId(), quantity);
            if (price > 0) {
                chr.gainMeso(price, false);
                mesoGained += price;
            }
            sold++;
        }
        return List.of("autosell: sold " + sold + " item" + (sold != 1 ? "s" : "")
                + " for " + mesoGained + " meso");
    }

    // !inspectsell (admin debug): rearrange a character's bag so each tab is laid out the way the bot
    // would shed it under bag pressure — slot 1 the most-disposable trash, the last slot the most
    // prized keep, working back from there. Ordering is the SSOT pressure-sale score per section
    // ({@link #inspectSellRank}): equips by RESV/HOARD/HLIM/TRASH bucket + tradeValueScore, USE by
    // JUNK/SHELF(keepValue)/RUNWAY/quest, ETC by sell-trash vs whitelist. The split point between
    // "would sell now" ({@link #collectSellTrashItems}) and "would keep" is the divider: keeps are
    // back-anchored to the tab's tail so every empty slot pools in the MIDDLE, not the end. A packed
    // tab NPC-sells one already-doomed item (illegal sale allowed, debug only) to open that gap.
    // Server-side slot rebuild like {@link #sortOwnAmmoSlots}; the F8 window reads it fresh on reopen.
    static List<String> inspectSellArrange(Character chr) {
        Client c = chr.getClient();
        if (c == null) {
            return List.of("inspectsell: target is offline");
        }
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        // SSOT classifiers: the exact verdicts/scores the real sell pipeline applies.
        Map<Item, BagEquipClass> eqClass = classifyBagEquips(null, chr);
        Map<Item, UseClass> useClass = classifyBagUse(chr);
        Set<Item> etcSell = Collections.newSetFromMap(new IdentityHashMap<>());
        etcSell.addAll(collectSellTrashEtcItems(chr));
        Set<Item> selling = Collections.newSetFromMap(new IdentityHashMap<>());
        selling.addAll(collectSellTrashItems(null, chr));
        Comparator<Item> byRank =
                Comparator.comparingDouble(it -> inspectSellRank(ii, it, eqClass, useClass, etcSell));

        List<String> lines = new ArrayList<>();
        for (InventoryType type : List.of(InventoryType.EQUIP, InventoryType.USE, InventoryType.ETC)) {
            Inventory inv = chr.getInventory(type);

            // Packed tab: NPC the most-disposable selling item (going anyway) so a divider can exist.
            long meso = 0;
            boolean soldForGap = false;
            if (inv.getNumFreeSlot() == 0) {
                Item victim = null;
                for (short i = 1; i <= inv.getSlotLimit(); i++) {
                    Item it = inv.getItem(i);
                    if (it != null && selling.contains(it)
                            && (victim == null || byRank.compare(it, victim) < 0)) {
                        victim = it;
                    }
                }
                short qty = victim == null ? 0 : sellTrashQuantity(victim);
                if (qty > 0) {
                    int price = ii.getPrice(victim.getItemId(), qty);
                    InventoryManipulator.removeFromSlot(c, type, (byte) victim.getPosition(), qty, false);
                    if (price > 0) {
                        chr.gainMeso(price, false);
                        meso += price;
                    }
                    selling.remove(victim);
                    soldForGap = true;
                }
            }

            inv.lockInventory();
            try {
                List<Item> sells = new ArrayList<>();
                List<Item> keeps = new ArrayList<>();
                for (short i = 1; i <= inv.getSlotLimit(); i++) {
                    Item it = inv.getItem(i);
                    if (it == null) {
                        continue;
                    }
                    (selling.contains(it) ? sells : keeps).add(it);
                }
                if (sells.isEmpty()) {
                    continue;   // nothing this tab would sell — leave it untouched
                }
                sells.sort(byRank);   // most disposable first
                keeps.sort(byRank);   // least prized first, most prized last

                for (Item it : sells) inv.removeSlot(it.getPosition());
                for (Item it : keeps) inv.removeSlot(it.getPosition());

                // Sells front-anchored (slot 1 = most trash); keeps back-anchored (last slot = most
                // prized). Every free slot falls in the middle band between them.
                short pos = 1;
                for (Item it : sells) { it.setPosition(pos++); inv.addItemFromDB(it); }
                short keepStart = (short) (inv.getSlotLimit() - keeps.size() + 1);
                pos = (short) Math.max(pos, keepStart);   // abut sells if the tab is genuinely full
                for (Item it : keeps) { it.setPosition(pos++); inv.addItemFromDB(it); }

                int gapSlots = inv.getSlotLimit() - sells.size() - keeps.size();
                lines.add(String.format("%s: %d sell | %d-slot gap | %d keep%s",
                        type.name().toLowerCase(), sells.size(), Math.max(0, gapSlots),
                        keeps.size(), soldForGap ? " (sold 1 for " + meso + " to open gap)" : ""));
            } finally {
                inv.unlockInventory();
            }
        }
        if (lines.isEmpty()) {
            lines.add("inspectsell: nothing the bot pipeline would sell");
        }
        return lines;
    }

    // Pressure-sale rank for !inspectsell: lower = shed sooner (front slots), higher = more prized
    // (tail slots). Each section reuses its own SSOT score, offset into a band so the section order
    // holds regardless of raw magnitudes: sell-now buckets sit below keep buckets, reserved/quest on
    // top. EQUIP TRASH<HLIM<HOARD<RESV by tradeValueScore; USE JUNK<SHELF(keepValue)<RUNWAY<quest;
    // ETC sell-trash<whitelist.
    private static double inspectSellRank(ItemInformationProvider ii, Item it,
            Map<Item, BagEquipClass> eqClass, Map<Item, UseClass> useClass, Set<Item> etcSell) {
        final double BAND = 1e12;
        int id = it.getItemId();
        switch (it.getInventoryType()) {
            case EQUIP -> {
                double score = it instanceof Equip e ? tradeValueScore(ii, e) : 0;
                BagEquipClass bc = eqClass.get(it);
                int band = bc == null ? 0 : switch (bc.status()) {
                    case TRASH -> 0;
                    case HLIM -> 1;
                    case HOARD -> 2;
                    case RESV_OTHER -> 3;
                    case RESV_SELF -> 4;
                };
                return band * BAND + score;
            }
            case USE -> {
                UseClass uc = useClass.get(it);
                if (uc == null) {
                    return 3 * BAND + id;   // quest/untradeable: can't be sold, most stuck → tail
                }
                return switch (uc.tier()) {
                    case JUNK -> 0 * BAND + sellPrice.price(id, it.getQuantity());
                    case SHELF -> 1 * BAND + uc.keepValue();
                    case RUNWAY -> 2 * BAND + sellPrice.price(id, it.getQuantity());
                };
            }
            default -> {   // ETC
                return (etcSell.contains(it) ? 0 : 1) * BAND + id;
            }
        }
    }

    private static String autoSellTypeLabel(InventoryType type) {
        return switch (type) {
            case EQUIP -> "Equip";
            case USE -> "Use";
            case ETC -> "Etc";
            default -> type.name();
        };
    }

    /** Equips use the same up-to-2-relevant-stats specifier as the bot loot-offer prompts
     *  ("+3 str +4 dex White Polyfeather Hat", actual stats), keyed on the ITEM's class
     *  (reqJob) since the seller is mostly unloading other jobs' gear; common gear (job 0)
     *  falls back to the seller's own job. Stackables show the quantity that would actually
     *  sell ("Scroll for Shield for DEF x4"). */
    static String describeAutoSellItem(ItemInformationProvider ii, Character audience, Item item) {
        if (item instanceof Equip) {
            return BotOfferManager.formatItemSpecifier(item, equipPerspectiveJobId(ii, item.getItemId(), audience));
        }
        String name = itemName(ii, item.getItemId());
        short quantity = sellTrashQuantity(item);
        return quantity > 1 ? name + " x" + quantity : name;
    }

    private static int equipPerspectiveJobId(ItemInformationProvider ii, int itemId, Character audience) {
        Map<String, Integer> stats = ii != null ? ii.getEquipStats(itemId) : null;
        int reqJobMask = stats != null ? stats.getOrDefault("reqJob", 0) : 0;
        return switch (reqJobMask) {
            case 1 -> 100;  // warrior
            case 2 -> 200;  // magician
            case 4 -> 300;  // bowman
            case 8 -> 400;  // thief
            case 16 -> 500; // pirate
            default -> audience != null && audience.getJob() != null ? audience.getJob().getId() : 0;
        };
    }

    private static final int AUTO_SELL_LINE_WIDTH = 110;

    private static void appendWrappedListLines(List<String> lines, String prefix, List<String> descs) {
        StringBuilder line = new StringBuilder(prefix);
        boolean first = true;
        for (String desc : descs) {
            String piece = first ? desc : ", " + desc;
            if (!first && line.length() + piece.length() > AUTO_SELL_LINE_WIDTH) {
                lines.add(line.toString());
                line = new StringBuilder("  ").append(desc);
            } else {
                line.append(piece);
            }
            first = false;
        }
        lines.add(line.toString());
    }

    // Debug classification of bag equips, derived from the SAME predicates and ranking the
    // sell pipeline uses (no parallel decision tree):
    //   RESV-SELF  reserved for the bot's own upgrades (collectPotentialSelfUpgradeItems)
    //   RESV-OTHER promised to other recipients (BotOfferManager)
    //   HOARD#r    kept for value, rank r on the bounded shelf (or above the never-sell gate)
    //   HLIM#r     kept for value but beyond the shelf and below the gate -> sells next trip
    //   TRASH      sells next trip
    enum BagEquipStatus { RESV_SELF, RESV_OTHER, HOARD, HLIM, TRASH }

    record BagEquipClass(BagEquipStatus status, int rank) {
        String label() {
            return switch (status) {
                case RESV_SELF -> "RESV-SELF";
                case RESV_OTHER -> "RESV-OTHER";
                case HOARD -> "HOARD#" + rank;
                case HLIM -> "HLIM#" + rank;
                case TRASH -> "TRASH";
            };
        }
    }

    static Map<Item, BagEquipClass> classifyBagEquips(BotEntry entry, Character bot) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        List<Item> all = new ArrayList<>();
        collectFromBag(bot, all, InventoryType.EQUIP, item -> true);
        Set<Item> selfKeep = BotEquipManager.collectPotentialSelfUpgradeItems(bot);
        return classifyBagEquips(ii, all, selfKeep,
                item -> entry != null && BotOfferManager.isReservedForOtherRecipients(entry, bot, item));
    }

    static Map<Item, BagEquipClass> classifyBagEquips(ItemInformationProvider ii, List<Item> bagEquips,
                                                      Set<Item> reservedSelf, Predicate<Item> reservedOther) {
        Map<Item, BagEquipClass> out = new IdentityHashMap<>();
        List<Equip> kept = new ArrayList<>();
        for (Item item : bagEquips) {
            if (!(item instanceof Equip equip)) {
                continue;
            }
            if (reservedSelf.contains(item)) {
                out.put(item, new BagEquipClass(BagEquipStatus.RESV_SELF, 0));
            } else if (reservedOther.test(item)) {
                out.put(item, new BagEquipClass(BagEquipStatus.RESV_OTHER, 0));
            } else if (shouldKeepForSellTrash(ii, equip)) {
                kept.add(equip);
            } else {
                out.put(item, new BagEquipClass(BagEquipStatus.TRASH, 0));
            }
        }
        List<Equip> ranked = rankKeptValuables(ii, kept);
        for (int i = 0; i < ranked.size(); i++) {
            Equip e = ranked.get(i);
            int rank = i + 1;
            boolean keeps = rank <= KEEP_VALUABLE_EQUIP_SLOTS
                    || tradeValueScore(ii, e) >= NEVER_SELL_TRADE_SCORE;
            out.put(e, new BagEquipClass(keeps ? BagEquipStatus.HOARD : BagEquipStatus.HLIM, rank));
        }
        return out;
    }

    // "inv debug" chat command: writes logs/bot-equip/invlog-<name>-<timestamp>.txt with the
    // verdict the REAL sell pipeline would apply to every bag item. Verdicts come from the
    // actual collect* outputs; reasons are coarse labels probed from the same predicates
    // (no second decision tree). Built to debug USE/ETC hoarding.
    static String inventoryDebug(BotEntry entry) {
        Character bot = entry != null ? entry.bot : null;
        if (bot == null) {
            return "no bot to dump";
        }
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        String safeName = bot.getName().replaceAll("[^a-zA-Z0-9_-]", "_");
        String filename = "invlog-" + safeName + "-" + now.format(BotEquipManager.EQUIP_LOG_FILE_FMT) + ".txt";

        StringBuilder sb = new StringBuilder(8192);
        sb.append("=== inventory dump ===\n");
        sb.append("time:    ").append(now.format(BotEquipManager.EQUIP_LOG_HEADER_FMT)).append('\n');
        sb.append("bot:     ").append(bot.getName())
          .append(" job=").append(bot.getJob())
          .append(" lv=").append(bot.getLevel()).append('\n');

        Map<Item, BagEquipClass> statuses = classifyBagEquips(entry, bot);
        sb.append("\n--- EQUIP ---\n");
        sb.append(String.format("%-3s %-30s %-7s %7s  %s%n", "pos", "name", "slot", "score", "STATUS"));
        int equipCount = 0;
        for (Item item : bot.getInventory(InventoryType.EQUIP).list()) {
            if (!(item instanceof Equip e)) {
                continue;
            }
            equipCount++;
            BagEquipClass c = statuses.get(item);
            String slot = ii.getEquipmentSlot(e.getItemId());
            sb.append(String.format("%-3d %-30s %-7s %7.1f  %s%n",
                    e.getPosition(), itemName(ii, e.getItemId()), slot == null ? "?" : slot,
                    tradeValueScore(ii, e), c == null ? "-" : c.label()));
        }

        // SELL = JUNK (always sold); SHELF#r = sold only when cramped, rank r worst-first (1 sells
        // first); RUNWAY = combat reserve, never auto-sold. unit/stack = NPC meso (per-unit / whole
        // stack); rechargeable ammo is valued per-set, so dup sets show stack meso but rank ~last.
        Map<Item, UseClass> useClasses = classifyBagUse(bot);
        sb.append("\n--- USE ---\n");
        sb.append(String.format("%-28s %-6s %-9s %8s %8s  %s%n",
                "name", "qty", "tier", "unit", "stack", "reason"));
        int useSell = 0;
        int useCount = 0;
        for (Item item : bot.getInventory(InventoryType.USE).list()) {
            useCount++;
            int id = item.getItemId();
            UseClass uc = useClasses.get(item);
            String tier;
            String reason;
            if (uc == null) {
                tier = "KEEP";
                reason = "quest-or-untradeable";
            } else if (uc.tier() == UseTier.JUNK) {
                tier = "SELL";
                reason = uc.reason();
                useSell++;
            } else if (uc.tier() == UseTier.RUNWAY) {
                tier = "RUNWAY";
                reason = uc.reason();
            } else {
                tier = "SHELF#" + uc.shelfRank();
                reason = uc.reason();
            }
            sb.append(String.format("%-28s x%-5d %-9s %8d %8d  %s%n",
                    itemName(ii, id), item.getQuantity(),
                    tier, sellPrice.price(id, 1), sellPrice.price(id, item.getQuantity()), reason));
        }

        Set<Item> sellingEtc = Collections.newSetFromMap(new IdentityHashMap<>());
        sellingEtc.addAll(collectSellTrashEtcItems(bot));
        sb.append("\n--- ETC ---\n");
        sb.append(String.format("%-30s %-6s %-5s %s%n", "name", "qty", "verd", "reason"));
        int etcSell = 0;
        int etcCount = 0;
        for (Item item : bot.getInventory(InventoryType.ETC).list()) {
            etcCount++;
            boolean sell = sellingEtc.contains(item);
            if (sell) {
                etcSell++;
            }
            sb.append(String.format("%-30s x%-5d %-5s %s%n",
                    itemName(ii, item.getItemId()), item.getQuantity(),
                    sell ? "SELL" : "KEEP", etcVerdictReason(bot, item, sell)));
        }

        try {
            java.nio.file.Files.createDirectories(BotEquipManager.EQUIP_LOG_DIR);
            java.nio.file.Files.writeString(BotEquipManager.EQUIP_LOG_DIR.resolve(filename), sb.toString());
        } catch (java.io.IOException e) {
            log.warn("Failed to write inventory dump", e);
            return "couldn't write the inventory dump, check server logs";
        }
        return String.format("inv dump: %s | equip %d, use sell %d/%d, etc sell %d/%d",
                filename, equipCount, useSell, useCount, etcSell, etcCount);
    }

    private static String itemName(ItemInformationProvider ii, int itemId) {
        String name = ii != null ? ii.getName(itemId) : null;
        if (name == null || name.isBlank()) {
            name = "id=" + itemId;
        }
        return name.length() > 30 ? name.substring(0, 30) : name;
    }

    // Coarse reason labels probing the same predicates collectSellTrashEtcItems applies.
    private static String etcVerdictReason(Character bot, Item item, boolean sell) {
        int id = item.getItemId();
        if (sell) {
            if (isOmokItem(id)) return "omok-sell";
            return isStaleQuestItem(bot, id) ? "quest-stale" : "sell";
        }
        if (!isSafeToDrop(bot, item)) {
            return isStaleQuestItem(bot, id) ? "quest-stale-but-unsellable" : "quest-or-untradeable";
        }
        if (SKILL_CONSUMED_ETC.contains(id)) {
            return "skill-consumed";
        }
        if (isMakerMaterial(id)) {
            return "maker-material";
        }
        if (isRareDrop(id)) {
            return "rare-drop";
        }
        if (keepCrystalLeftover(bot, item)) {
            return "crystal-leftover-keep";
        }
        return "no-npc-price";
    }

    /** Per-bot cache of the (expensive) equip trade classification. {@code isReservedForOtherRecipients}
     *  runs the optimizer-reserve check for every bag equip against every party member, so a cramped
     *  bag re-classifying each tick melts a timer thread (~150ms x N ticks). Cache the result and
     *  reuse it until the bag changes (signature) or a short TTL lapses (teammate-gear drift, which
     *  the bag signature can't see). Reused everywhere, including the shop sell sequence. */
    private static final long EQUIP_TRADE_GROUPS_TTL_MS = 3_000L;

    static final class EquipTradeGroupsCache {
        private final long bagSignature;
        private final long computedAtMs;
        private final EquipTradeGroups groups;

        EquipTradeGroupsCache(long bagSignature, long computedAtMs, EquipTradeGroups groups) {
            this.bagSignature = bagSignature;
            this.computedAtMs = computedAtMs;
            this.groups = groups;
        }
    }

    // Cheap, order-independent fingerprint of the EQUIP bag: which slots hold which items, plus a
    // light stat fold so an in-place scroll/upgrade also invalidates. Slot positions are unique
    // within a tab, so XOR-combining per-item hashes can't collide on a re-ordered list().
    private static long equipBagSignature(Character bot) {
        Inventory inv = bot != null ? bot.getInventory(InventoryType.EQUIP) : null;
        if (inv == null) {
            return 0L;
        }
        long sig = 0L;
        int n = 0;
        for (Item item : inv.list()) {
            long h = ((long) item.getItemId() * 2654435761L) ^ ((long) item.getPosition() * 40503L);
            if (item instanceof Equip e) {
                h ^= ((long) (e.getStr() + e.getDex() * 7 + e.getInt() * 13 + e.getLuk() * 17
                        + e.getWatk() * 23 + e.getMatk() * 29)) << 24;
            }
            sig ^= h;
            n++;
        }
        return sig ^ ((long) n << 56);
    }

    private static EquipTradeGroups classifyEquipTradeGroups(BotEntry entry, Character bot) {
        // No entry = @autosell preview on a real player's character: always classify fresh (no bot
        // state to cache on, and the admin wants a live answer).
        if (entry == null || bot == null) {
            return computeEquipTradeGroups(entry, bot);
        }
        long signature = equipBagSignature(bot);
        long now = System.currentTimeMillis();
        EquipTradeGroupsCache cached = entry.cachedEquipTradeGroups;
        if (cached != null && cached.bagSignature == signature
                && now - cached.computedAtMs < EQUIP_TRADE_GROUPS_TTL_MS) {
            return cached.groups;
        }
        EquipTradeGroups groups = computeEquipTradeGroups(entry, bot);
        // Single volatile swap of an immutable holder: a concurrent classify (trades run on timer
        // threads) at worst recomputes once, never reads a torn signature/groups pair.
        entry.cachedEquipTradeGroups = new EquipTradeGroupsCache(signature, now, groups);
        return groups;
    }

    private static EquipTradeGroups computeEquipTradeGroups(BotEntry entry, Character bot) {
        long startedAt = profileTradeCategory("equips") ? System.nanoTime() : 0L;
        long bagScanStartedAt = startedAt != 0L ? System.nanoTime() : 0L;
        List<Item> all = new ArrayList<>();
        collectFromBag(bot, all, InventoryType.EQUIP, item -> true);
        long bagScanNs = startedAt != 0L ? System.nanoTime() - bagScanStartedAt : 0L;
        long selfKeepStartedAt = startedAt != 0L ? System.nanoTime() : 0L;
        Set<Item> selfKeep = BotEquipManager.collectPotentialSelfUpgradeItems(bot);
        long selfKeepNs = startedAt != 0L ? System.nanoTime() - selfKeepStartedAt : 0L;

        List<Item> normal = new ArrayList<>();
        List<Item> reservedForOther = new ArrayList<>();
        List<Item> reservedForSelf = new ArrayList<>();
        long reservedOtherNs = 0L;
        int reservedOtherChecks = 0;
        int reservedOtherHits = 0;
        for (Item item : all) {
            if (selfKeep.contains(item)) {
                reservedForSelf.add(item);
                continue;
            }
            long reservedOtherStartedAt = startedAt != 0L ? System.nanoTime() : 0L;
            boolean isOther = BotOfferManager.isReservedForOtherRecipients(entry, bot, item);
            if (startedAt != 0L) {
                reservedOtherNs += System.nanoTime() - reservedOtherStartedAt;
                reservedOtherChecks++;
                if (isOther) {
                    reservedOtherHits++;
                }
            }
            if (isOther) {
                reservedForOther.add(item);
            } else {
                normal.add(item);
            }
        }

        long sortStartedAt = startedAt != 0L ? System.nanoTime() : 0L;
        List<Item> normalSorted = sortEquipsByItemId(normal);
        List<Item> reservedForOtherSorted = sortEquipsByItemId(reservedForOther);
        List<Item> reservedForSelfSorted = sortEquipsByTradeScore(reservedForSelf, bot);
        long sortNs = startedAt != 0L ? System.nanoTime() - sortStartedAt : 0L;
        if (startedAt != 0L) {
            long elapsedNs = System.nanoTime() - startedAt;
            if (elapsedNs >= TRADE_COMMAND_PROFILE_WARN_NS) {
                String botName = bot != null ? bot.getName() : "?";
                String ownerName = entry != null && entry.owner != null ? entry.owner.getName() : "?";
                log.warn(
                        "Slow equip trade classification: took {} ms bot={} owner={} bagItems={} selfKeep={} normalItems={} reservedOtherItems={} reservedSelfItems={} bagScanMs={} selfKeepMs={} reservedOtherMs={} reservedOtherChecks={} reservedOtherHits={} sortMs={}",
                        String.format("%.1f", elapsedNs / 1_000_000.0),
                        botName,
                        ownerName,
                        all.size(),
                        selfKeep.size(),
                        normalSorted.size(),
                        reservedForOtherSorted.size(),
                        reservedForSelfSorted.size(),
                        String.format("%.1f", bagScanNs / 1_000_000.0),
                        String.format("%.1f", selfKeepNs / 1_000_000.0),
                        String.format("%.1f", reservedOtherNs / 1_000_000.0),
                        reservedOtherChecks,
                        reservedOtherHits,
                        String.format("%.1f", sortNs / 1_000_000.0));
            }
        }
        return new EquipTradeGroups(normalSorted, reservedForOtherSorted, reservedForSelfSorted);
    }

    private static boolean isOwnClassEquip(Character bot, ItemInformationProvider ii, Equip equip) {
        return BotEquipManager.isOwnClassEquip(bot, ii, equip);
    }

    static boolean shouldKeepForSellTrash(ItemInformationProvider ii, Equip equip) {
        if (equip.getLevel() > 0) {
            return true;
        }
        Map<String, Integer> stats = ii != null ? ii.getEquipStats(equip.getItemId()) : null;
        if (ItemConstants.isWeapon(equip.getItemId())) {
            Equip baseEquip = ii != null ? (Equip) ii.getEquipById(equip.getItemId()) : null;
            if (hasProtectedSellTrashWeaponStat(equip, baseEquip)) {
                return true;
            }
        } else if (equip.getWatk() > 0) {
            return true;
        }
        return hasProtectedSellTrashStat(stats, equip, 6, 10);
    }

    // A stat protects an equip from being trashed only if it has been improved above the item's
    // WZ base (>= aboveBaseThreshold AND strictly above base), or it is high enough on its own
    // (>= pureThreshold) regardless of base. Base stat values come straight from the WZ stats map
    // (the "inc"-stripped STR/DEX/INT/LUK keys).
    static boolean hasProtectedSellTrashStat(Map<String, Integer> stats, Equip equip, int aboveBaseThreshold, int pureThreshold) {
        if (stats == null || equip == null) {
            return false;
        }

        boolean str = statProtected(equip.getStr(), stats.getOrDefault("STR", 0), aboveBaseThreshold, pureThreshold);
        boolean dex = statProtected(equip.getDex(), stats.getOrDefault("DEX", 0), aboveBaseThreshold, pureThreshold);
        boolean intt = statProtected(equip.getInt(), stats.getOrDefault("INT", 0), aboveBaseThreshold, pureThreshold);
        boolean luk = statProtected(equip.getLuk(), stats.getOrDefault("LUK", 0), aboveBaseThreshold, pureThreshold);

        int reqJob = stats.getOrDefault("reqJob", 0);
        if (reqJob == 0) {
            return str || dex || intt || luk;
        }

        return ((reqJob & 0x1) != 0 && (str || dex))
                || ((reqJob & 0x2) != 0 && (intt || luk))
                || ((reqJob & 0x4) != 0 && (dex || str))
                || ((reqJob & 0x8) != 0 && (luk || dex))
                || ((reqJob & 0x10) != 0 && (str || dex));
    }

    private static boolean statProtected(int value, int base, int aboveBaseThreshold, int pureThreshold) {
        return value >= pureThreshold || (value >= aboveBaseThreshold && value > base);
    }

    static boolean hasProtectedSellTrashWeaponStat(Equip equip, Equip baseEquip) {
        if (equip == null || baseEquip == null) {
            return false;
        }
        // Either attack axis counts: an above-base MAD roll on an any-job weapon (e.g. the
        // reqJob-0 Black Umbrella, a 1H sword with base MAD 85) is mage trade stock even when
        // its WATK rolled clean — don't key the protected axis on the weapon's reqJob mask.
        return equip.getWatk() - baseEquip.getWatk() >= 4
                || equip.getMatk() - baseEquip.getMatk() >= 4;
    }

    /** Score used to order own-class equips worst-to-best: 4*watk + matk + main + sec. */
    private static int equipTradeScore(Equip e, Job job) {
        int main, sec;
        if (BotEquipManager.isMageJob(job)) {
            main = e.getInt(); sec = e.getLuk();
        } else if (job != null && (job.isA(Job.BOWMAN)
                || job == Job.GUNSLINGER || job == Job.OUTLAW || job == Job.CORSAIR)) {
            main = e.getDex(); sec = e.getStr();
        } else if (job != null && job.isA(Job.THIEF)) {
            main = e.getLuk(); sec = e.getDex();
        } else {
            main = e.getStr(); sec = e.getDex();
        }
        return 4 * e.getWatk() + e.getMatk() + main + sec;
    }

    private static List<Item> sortItemsByItemId(List<Item> items) {
        if (items.size() <= 1) {
            return items;
        }
        List<Item> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparingInt(Item::getItemId).thenComparingInt(Item::getPosition));
        return sorted;
    }

    private static int dropFromBag(Character bot, InventoryType type, Predicate<Item> filter) {
        Inventory inv = bot.getInventory(type);
        List<Short> slots = new ArrayList<>();
        for (short slot = 1; slot <= inv.getSlotLimit(); slot++) {
            Item item = inv.getItem(slot);
            if (item != null && isSafeToDrop(item) && filter.test(item)) slots.add(slot);
        }
        int count = 0;
        for (short slot : slots) {
            Item item = inv.getItem(slot);
            if (item == null) continue;
            InventoryManipulator.drop(bot.getClient(), type, slot, item.getQuantity());
            count++;
        }
        return count;
    }

    static boolean isSafeToDrop(Item item) {
        if (untradeable.test(item) && !YamlConfig.config.server.UNTRADEABLE_ITEMS_TRADEABLE) return false;
        if (questItem.test(item.getItemId())) return false;
        return true;
    }

    /** Bot-aware safe-to-drop: like {@link #isSafeToDrop(Item)}, but a quest item that is STALE for
     *  THIS bot ({@link #isStaleQuestItem}) is allowed through — the base predicate excludes ALL
     *  quest items forever, which clogs ETC/USE with items the bot can never use again. Only the
     *  USE/ETC sell-trash collectors call this overload; the EQUIP path keeps the strict
     *  {@link #isSafeToDrop(Item)} so it never touches quest gear. Untradeables are still excluded. */
    static boolean isSafeToDrop(Character bot, Item item) {
        if (untradeable.test(item) && !YamlConfig.config.server.UNTRADEABLE_ITEMS_TRADEABLE) return false;
        if (questItem.test(item.getItemId()) && !isStaleQuestItem(bot, item.getItemId())) return false;
        return true;
    }

    // ---- stale quest items (Feature B) -------------------------------------------------------

    /** Severely-outleveled margin: a quest is "way past" the bot when the bot's level is at least
     *  the quest's level cap plus this. Wide on purpose - false negatives (keep) are fine, false
     *  positives (sell a needed item) are not. */
    static final int STALE_OUTLEVEL_MARGIN = 30;

    /** Quest status for the stale check, behind a seam so tests don't need WZ. Reuses the same
     *  {@link BotQuestManager#gate} SSOT the quest loop drives (started/completed reads). */
    interface QuestStatusLookup {
        boolean isStarted(Character bot, int questId);
        boolean isCompleted(Character bot, int questId);
    }

    static QuestStatusLookup questStatus = new QuestStatusLookup() {
        @Override public boolean isStarted(Character bot, int questId) {
            return BotQuestManager.gate.isStarted(bot, questId);
        }
        @Override public boolean isCompleted(Character bot, int questId) {
            return BotQuestManager.gate.isCompleted(bot, questId);
        }
    };

    /** Item -> quests that require it (start or complete); seam over {@link BotQuestIndex}. */
    @FunctionalInterface
    interface QuestReqsLookup {
        List<BotQuestIndex.QuestItemReq> reqs(int itemId);
    }

    static QuestReqsLookup questReqsLookup = BotQuestIndex::questsRequiringItem;

    /** Whether a quest has been proven unfinishable for this bot — the turn-in NPC is on an
     *  unreachable map, or {@code complete()} refused to register (scripted/edge quests). Reuses the
     *  per-entry {@link BotEntry#buggedQuestIds} SSOT (set by {@link BotQuestManager#markQuestBugged})
     *  rather than re-deriving reachability here. Seam so tests stay BotManager-free. */
    static java.util.function.BiPredicate<Character, Integer> questBugged = (bot, questId) -> {
        BotEntry entry = BotManager.getInstance().getEntryByBotCharId(bot.getId());
        return entry != null && entry.buggedQuestIds.contains(questId);
    };

    /**
     * Is {@code itemId} a quest item this bot no longer needs - so it can be cleared as clutter
     * instead of being kept forever? CONSERVATIVE: true only when it IS a quest item AND every
     * indexed quest that requires it is, for this bot, disposable - COMPLETED, severely outleveled,
     * or proven unfinishable ({@link #questBugged}: unreachable turn-in NPC, or a complete() that
     * won't register). A quest the bot has STARTED still keeps the item UNLESS it's bugged (a started
     * quest whose NPC the bot can never reach is dead weight). Any of:
     * <ul>
     *   <li>not a quest item, or used by NO indexed quest -> NOT stale (out of scope);</li>
     *   <li>ANY using-quest is STARTED and not bugged -> NOT stale (needed right now);</li>
     *   <li>any using-quest is still doable (not completed, not past, not bugged) -> NOT stale.</li>
     * </ul>
     * "Severely outleveled" needs a real level cap ({@code > 0}); a quest with no level info is
     * undeterminable and treated as still-doable (kept). The permanently-unstartable-prereq branch
     * is deliberately NOT implemented - {@code canStart} fails for under-level bots that will grow
     * into the quest, so it is too false-positive-prone (see report).
     */
    static boolean isStaleQuestItem(Character bot, int itemId) {
        if (bot == null || !questItem.test(itemId)) {
            return false;
        }
        List<BotQuestIndex.QuestItemReq> reqs = questReqsLookup.reqs(itemId);
        if (reqs.isEmpty()) {
            return false; // used by no indexed quest -> not our scope
        }
        // A STARTED, still-finishable quest keeps the item; a started-but-bugged one does not.
        for (BotQuestIndex.QuestItemReq r : reqs) {
            if (questStatus.isStarted(bot, r.questId()) && !questBugged.test(bot, r.questId())) {
                return false;
            }
        }
        // Stale only if EVERY using-quest is done-or-past or proven unfinishable for this bot.
        for (BotQuestIndex.QuestItemReq r : reqs) {
            if (!isQuestDoneOrPast(bot, r) && !questBugged.test(bot, r.questId())) {
                return false;
            }
        }
        return true;
    }

    /** A single using-quest is "done or past" for the bot: COMPLETED, or severely outleveled with
     *  a known level cap. Undeterminable (no level cap) counts as still-doable (kept). */
    private static boolean isQuestDoneOrPast(Character bot, BotQuestIndex.QuestItemReq r) {
        if (questStatus.isCompleted(bot, r.questId())) {
            return true;
        }
        int cap = Math.max(r.lvmax(), r.lvmin());
        return cap > 0 && bot.getLevel() >= cap + STALE_OUTLEVEL_MARGIN;
    }

    // Test seams (see sellPrice et al.): both lookups go through ItemInformationProvider.
    static IntPredicate questItem = id -> ItemInformationProvider.getInstance().isQuestItem(id);
    static Predicate<Item> untradeable = Item::isUntradeable;

    private static void reply(BotEntry entry, Character bot, int count, String noun) {
        BotManager.getInstance().botReply(entry,
                count > 0 ? "dropped " + count + " " + noun + (count != 1 ? "s" : "") + "!"
                          : "no " + noun + "s to drop");
    }

    static boolean isMesoCategory(String category) {
        return category != null && (category.equals("mesos") || category.startsWith("mesos:"));
    }

    private static int requestedTradeMesos(String category) {
        if (!isMesoCategory(category)) {
            return 0;
        }
        if ("mesos".equals(category)) {
            return -1;
        }

        try {
            return Integer.parseInt(category.substring("mesos:".length()));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String notEnoughMesosReply(int requestedMesos, int currentMesos) {
        return "i only have " + GameConstants.numberWithCommas(currentMesos)
                + " mesos rn, not " + GameConstants.numberWithCommas(requestedMesos);
    }

    // ─── Pot-share helpers ────────────────────────────────────────────────────

    /**
     * Recovery score used to sort pots "worst first" (ascending).
     * Flat HP/MP values come first; hpRate/mpRate pots score 1 000 000+ so they're
     * always considered better than any flat-value pot. Within each tier lower = worse.
     */
    private static int potRecoveryScore(int itemId, boolean forHp) {
        StatEffect eff = itemEffect(itemId);
        if (eff == null) return Integer.MAX_VALUE;
        if (forHp) {
            if (eff.getHpRate() > 0) return 1_000_000 + (int) (eff.getHpRate() * 1000);
            return eff.getHp();
        } else {
            if (eff.getMpRate() > 0) return 1_000_000 + (int) (eff.getMpRate() * 1000);
            return eff.getMp();
        }
    }

    /**
     * Collects the donor bot's worst recovery pots (sorted ascending by recovery score)
     * up to {@code maxQty} total quantity or 9 item stacks, whichever limit is reached first.
     * Only pure recovery pots are included (buff pots excluded via isRecoveryPotion).
     */
    static List<Item> collectPotShareItems(Character donorBot, boolean forHp, int maxQty) {
        if (maxQty <= 0) return List.of();
        List<Item> candidates = new ArrayList<>();
        Inventory useInv = donorBot.getInventory(InventoryType.USE);
        for (short slot = 1; slot <= useInv.getSlotLimit(); slot++) {
            Item item = useInv.getItem(slot);
            if (item == null || !isRecoveryPotion(item.getItemId())) continue;
            StatEffect eff = itemEffect(item.getItemId());
            if (eff == null) continue;
            if (forHp  && eff.getHp() == 0 && eff.getHpRate() == 0) continue;
            if (!forHp && eff.getMp() == 0 && eff.getMpRate() == 0) continue;
            candidates.add(item);
        }
        candidates.sort((a, b) -> Integer.compare(
                potRecoveryScore(a.getItemId(), forHp),
                potRecoveryScore(b.getItemId(), forHp)));
        List<Item> result = new ArrayList<>();
        int totalQty = 0;
        for (Item item : candidates) {
            if (result.size() >= 9 || totalQty >= maxQty) break;
            result.add(item);
            totalQty += item.getQuantity();
        }
        return result;
    }

    /** Initiates a bot-to-bot pot-share trade (single batch; donor auto-confirms). */
    static void startPotShareTransfer(List<Item> items, Character recipient, BotEntry entry, Character bot, int maxQty) {
        if (items.isEmpty()) return;
        if (bot.getTrade() != null || entry.pendingTradeCategory != null || recipient.getTrade() != null) {
            if (entry.pendingBotTradeRetry == null) {
                entry.pendingBotTradeRetry = () -> startPotShareTransfer(items, recipient, entry, bot, maxQty);
                entry.pendingBotTradeRetryMs = BotMovementManager.delayAfterCurrentTick(10_000);
            }
            return;
        }
        entry.pendingPotShareBudget = maxQty;
        startTradeSequence("pot_share", recipient, items, 0, true, entry, bot);
    }

    static List<Item> collectAmmoShareItems(Character donorBot, WeaponType needyWeaponType, int maxQty) {
        if (maxQty <= 0) return List.of();
        List<Item> candidates = new ArrayList<>();
        Inventory useInv = donorBot.getInventory(InventoryType.USE);
        for (short slot = 1; slot <= useInv.getSlotLimit(); slot++) {
            Item item = useInv.getItem(slot);
            if (item == null || !isAmmoForWeapon(item.getItemId(), needyWeaponType)) {
                continue;
            }
            candidates.add(item);
        }
        candidates.sort(Comparator
                .comparingInt((Item item) -> ItemInformationProvider.getInstance().getWatkForProjectile(item.getItemId()))
                .thenComparingInt(Item::getItemId));

        List<Item> result = new ArrayList<>();
        int totalQty = 0;
        for (Item item : candidates) {
            result.add(item);
            totalQty += item.getQuantity();
            if (result.size() >= 9 || totalQty >= maxQty) {
                break;
            }
        }
        return result;
    }

    static void startAmmoShareTransfer(List<Item> items, Character recipient, BotEntry entry, Character bot, int maxQty) {
        if (items.isEmpty()) return;
        if (bot.getTrade() != null || entry.pendingTradeCategory != null || recipient.getTrade() != null) {
            if (entry.pendingBotTradeRetry == null) {
                entry.pendingBotTradeRetry = () -> startAmmoShareTransfer(items, recipient, entry, bot, maxQty);
                entry.pendingBotTradeRetryMs = BotMovementManager.delayAfterCurrentTick(10_000);
            }
            return;
        }
        entry.pendingPotShareBudget = maxQty;
        startTradeSequence("ammo_share", recipient, items, 0, true, entry, bot);
    }

    static List<Item> collectRockShareItems(Character donorBot, int rockId, int maxQty) {
        if (maxQty <= 0 || rockId <= 0) return List.of();
        Inventory inv = donorBot.getInventory(ItemConstants.getInventoryType(rockId));
        List<Item> result = new ArrayList<>();
        int totalQty = 0;
        for (short slot = 1; slot <= inv.getSlotLimit(); slot++) {
            Item item = inv.getItem(slot);
            if (item == null || item.getItemId() != rockId) continue;
            result.add(item);
            totalQty += item.getQuantity();
            if (result.size() >= 9 || totalQty >= maxQty) break;
        }
        return result;
    }

    static void startRockShareTransfer(List<Item> items, Character recipient, BotEntry entry, Character bot, int maxQty) {
        if (items.isEmpty()) return;
        if (bot.getTrade() != null || entry.pendingTradeCategory != null || recipient.getTrade() != null) {
            if (entry.pendingBotTradeRetry == null) {
                entry.pendingBotTradeRetry = () -> startRockShareTransfer(items, recipient, entry, bot, maxQty);
                entry.pendingBotTradeRetryMs = BotMovementManager.delayAfterCurrentTick(10_000);
            }
            return;
        }
        entry.pendingPotShareBudget = maxQty;
        startTradeSequence("rock_share", recipient, items, 0, true, entry, bot);
    }

    private static boolean isAmmoForWeapon(int itemId, WeaponType weaponType) {
        return switch (weaponType) {
            case BOW -> ItemConstants.isArrowForBow(itemId);
            case CROSSBOW -> ItemConstants.isArrowForCrossBow(itemId);
            case CLAW -> ItemConstants.isThrowingStar(itemId);
            case GUN -> ItemConstants.isBullet(itemId);
            default -> false;
        };
    }

    private static boolean isTradeAmmoItem(int itemId) {
        return ammoWeaponType(itemId) != null;
    }

    private static WeaponType ammoWeaponType(int itemId) {
        if (ItemConstants.isArrowForBow(itemId)) {
            return WeaponType.BOW;
        }
        if (ItemConstants.isArrowForCrossBow(itemId)) {
            return WeaponType.CROSSBOW;
        }
        if (ItemConstants.isThrowingStar(itemId)) {
            return WeaponType.CLAW;
        }
        if (ItemConstants.isBullet(itemId)) {
            return WeaponType.GUN;
        }
        return null;
    }

    private static WeaponType tradeAmmoWeaponType(Character bot) {
        WeaponType weaponType = BotAttackExecutionProvider.getEquippedWeaponType(bot);
        return switch (weaponType) {
            case BOW, CROSSBOW, CLAW, GUN -> weaponType;
            default -> null;
        };
    }
}
