package server.bots;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import client.Character;
import client.inventory.Equip;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import server.ItemInformationProvider;
import server.Trade;
import server.bots.BotMarketGrammar.Kind;
import server.bots.BotMarketGrammar.Offer;
import server.bots.BotMarketLedger.EventKind;

/**
 * Shout-driven direct trades (living-economy S3). Bots poll the {@link BotMarketShoutBus} on their AI
 * tick, match a heard {@code S>}/{@code B>} against what they want or hold, and run a priced
 * equip&lt;-&gt;meso swap over the vanilla {@link Trade}: one side stages the equip, the other stages the
 * meso, and each side confirms only after verifying the counter-stage (accept-at-ask; no counters —
 * the full haggle machine is S4, design 8.5). Bots also occasionally EMIT an {@code S>} for a surplus
 * equip so bot&lt;-&gt;bot trades happen with no human present.
 *
 * <p><b>v1 is EQUIP-scoped.</b> Equips carry real per-piece willingness-to-pay
 * ({@link BotScrollManager#equipBuyCeilingMeso}) and willingness-to-sell
 * ({@link BotScrollManager#equipMarketQuote}), so no parallel pricing is needed — and the design's own
 * examples (glove / ilbis / fish spear) are all equips. Consumable/stack shout-trading is a later
 * slice. Every item and meso movement rides {@code Trade}'s staged-debit + refund-on-cancel path, and
 * each side locks only after it has both staged its own side and validated the partner's, so a
 * declined or timed-out window never dupes or loses (invariant 5).
 */
public final class BotShoutTradeManager {

    /** A committed deal the initiator registers so the shout SPEAKER (the responder) recognizes the
     *  incoming invite as this negotiated swap rather than a random trade request. */
    private record Deal(int buyerId, int sellerId, Offer offer, long expiresAt) {}

    /** Keyed by RESPONDER (shout speaker) char id — the party that will receive the invite. */
    private static final Map<Integer, Deal> dealsByResponder = new ConcurrentHashMap<>();

    private static final long TRADE_DEADLINE_MS = 20_000L;   // give up on a stalled window
    private static final long DEAL_TTL_MS = 15_000L;         // responder must be invited within this
    private static final int EMIT_MIN_MS = 4 * 60_000;       // per-bot shout cooldown (minutes)
    private static final int EMIT_MAX_MS = 9 * 60_000;

    private BotShoutTradeManager() {}

    static void tick(BotEntry entry, Character bot, boolean runAiTick) {
        long now = System.currentTimeMillis();
        if (entry.shoutTradeActive()) {
            driveTrade(entry, bot, now);
            return;
        }
        if (bot.getTrade() != null) {
            tryClaimResponder(entry, bot, now); // an incoming invite may be a deal for us to answer
            return;
        }
        if (!runAiTick || entry.marketBusy) {
            return;
        }
        if (tryMatchHeardShout(entry, bot, now)) {
            return;
        }
        maybeEmitShout(entry, bot, now);
    }

    /** Drop any pending shout-deal awaiting a departing/despawning character (map-leave or logout). */
    public static void forget(int charId) {
        dealsByResponder.remove(charId);
    }

    // ── matching (subscriber) ─────────────────────────────────────────────────

    private static boolean tryMatchHeardShout(BotEntry entry, Character bot, long now) {
        List<BotMarketShoutBus.Shout> shouts =
                BotMarketShoutBus.getInstance().active(bot.getMapId(), bot.getId(), now);
        if (shouts.isEmpty()) {
            return false;
        }
        BotMarketBook book = BotMarketBook.of(entry, bot);
        for (BotMarketShoutBus.Shout s : shouts) {
            Offer o = s.offer();
            if (o.kind() != Kind.PRICE_CHECK && o.priceMeso() > 0) {
                // hearing an advertisement is weak evidence (design sec 4)
                book.observe(BotMarketMath.priceKey(o.itemId(), 0), o.priceMeso(), BotMarketMath.W_SHOUT, now);
            }
            if (!isEquip(o.itemId())) {
                continue; // v1: equips only
            }
            Character speaker = bot.getMap().getCharacterById(s.speakerId());
            if (speaker == null || speaker.getTrade() != null) {
                continue; // left the map, or already busy in a trade
            }
            if (o.kind() == Kind.SELL && plausibleBuy(bot, o)) {
                if (!BotMarketShoutBus.getInstance().claim(bot.getMapId(), s.speakerId(), o)) {
                    continue; // another bot claimed it first
                }
                commit(entry, bot, speaker, o, false, null, now); // I buy
                return true;
            }
            if (o.kind() == Kind.BUY) {
                Equip mine = findSellableEquip(entry, bot, o);
                if (mine != null) {
                    if (!BotMarketShoutBus.getInstance().claim(bot.getMapId(), s.speakerId(), o)) {
                        continue; // another bot claimed it first
                    }
                    commit(entry, bot, speaker, o, true, mine, now); // I sell
                    return true;
                }
            }
        }
        return false;
    }

    /** Coarse pre-filter: the bot can afford it and a clean copy of this equip would upgrade some
     *  slot (empty or better than worn). The strict per-roll check runs at confirm on the actual
     *  staged piece — this only avoids opening a window the bot obviously can't/won't honor. */
    private static boolean plausibleBuy(Character bot, Offer o) {
        if (bot.getMeso() < o.priceMeso()) {
            return false;
        }
        Equip clean = cleanEquip(o.itemId());
        return clean != null && BotScrollManager.equipBuyCeilingMeso(bot, clean) > 0;
    }

    /** The cheapest marketable equip the bot holds matching the buy shout whose market value the
     *  offered price covers — sell the worst-rolled qualifying piece first. Null = nothing to sell. */
    private static Equip findSellableEquip(BotEntry entry, Character bot, Offer o) {
        Equip best = null;
        long bestValue = Long.MAX_VALUE;
        for (Equip eq : BotInventoryManager.collectMarketableEquips(entry, bot)) {
            if (eq.getItemId() != o.itemId()) {
                continue;
            }
            long value = BotScrollManager.equipMarketQuote(entry, bot, eq).curveQuoteMeso();
            if (value > 0 && o.priceMeso() >= value && value < bestValue) {
                best = eq;
                bestValue = value;
            }
        }
        return best;
    }

    private static void commit(BotEntry entry, Character bot, Character speaker, Offer o,
                               boolean selling, Equip sellEquip, long now) {
        entry.shoutTradePartnerId = speaker.getId();
        entry.shoutTradeOffer = o;
        entry.shoutTradeSelling = selling;
        entry.shoutTradeInitiator = true;
        entry.shoutTradeSellEquip = sellEquip;
        entry.shoutTradeInvited = false;
        entry.shoutTradeStaged = false;
        entry.shoutTradeDeadlineMs = now + TRADE_DEADLINE_MS;
        int buyerId = selling ? speaker.getId() : bot.getId();
        int sellerId = selling ? bot.getId() : speaker.getId();
        dealsByResponder.put(speaker.getId(), new Deal(buyerId, sellerId, o, now + DEAL_TTL_MS));
    }

    // ── responder (the shout speaker being invited) ───────────────────────────

    private static void tryClaimResponder(BotEntry entry, Character bot, long now) {
        Trade trade = bot.getTrade();
        if (trade == null || trade.getPartner() == null || trade.getNumber() != 1) {
            return; // not an incoming invite (slot 1) — leave it to the manual/peer trade tick
        }
        Deal d = dealsByResponder.get(bot.getId());
        if (d == null || d.expiresAt() <= now) {
            return;
        }
        int otherId = d.sellerId() == bot.getId() ? d.buyerId() : d.sellerId();
        Character partner = trade.getPartner().getChr();
        if (partner.getId() != otherId) {
            return; // invite from someone unrelated to our deal
        }
        boolean selling = d.sellerId() == bot.getId();
        Equip sell = selling ? findSellableEquip(entry, bot, d.offer()) : null;
        if (selling && sell == null) {
            return; // no longer have the piece — let the invite lapse/cancel on the initiator's clock
        }
        dealsByResponder.remove(bot.getId());
        entry.shoutTradePartnerId = partner.getId();
        entry.shoutTradeOffer = d.offer();
        entry.shoutTradeSelling = selling;
        entry.shoutTradeInitiator = false;
        entry.shoutTradeSellEquip = sell;
        entry.shoutTradeInvited = true; // window already exists
        entry.shoutTradeStaged = false;
        entry.shoutTradeDeadlineMs = now + TRADE_DEADLINE_MS;
        Trade.visitTrade(bot, partner); // accept the invite → full window
    }

    // ── the shared window state machine (both initiator and responder) ────────

    private static void driveTrade(BotEntry entry, Character bot, long now) {
        if (now > entry.shoutTradeDeadlineMs) {
            abort(entry, bot);
            return;
        }
        Character partner = bot.getMap() == null ? null
                : bot.getMap().getCharacterById(entry.shoutTradePartnerId);
        Trade trade = bot.getTrade();
        if (trade == null) {
            // The window is gone. If I had confirmed my side, the symmetric deal was guaranteed to
            // complete (my terms were met => the partner's were too => they lock too); treat it as
            // done. If I never locked, the partner cancelled — no clearing happened.
            if (entry.shoutTradeLocked) {
                finish(entry, bot, now);
            } else if (entry.shoutTradeInvited) {
                abort(entry, bot); // invite fell through / partner declined before we cleared
            } else if (partner == null || partner.getTrade() != null) {
                abort(entry, bot);
            } else {
                Trade.startTrade(bot);
                Trade.inviteTrade(bot, partner);
                entry.shoutTradeInvited = true;
            }
            return;
        }
        if (trade.getPartner() == null || !trade.isFullTrade()) {
            return; // waiting for the partner to accept
        }
        if (!entry.shoutTradeStaged) {
            if (!stageMySide(entry, bot, trade)) {
                abort(entry, bot);
                return;
            }
            entry.shoutTradeStaged = true;
            return;
        }
        if (partnerMeetsTerms(entry, bot, trade)) {
            Trade.completeTrade(bot); // locks my side; the exchange fires once both sides are locked
            entry.shoutTradeLocked = true;
            if (bot.getTrade() == null) {
                finish(entry, bot, now); // I was the second to lock — cleared this tick
            }
            return;
        }
        if (trade.isPartnerConfirmed()) {
            abort(entry, bot); // partner locked terms that don't meet the deal
        }
    }

    private static boolean stageMySide(BotEntry entry, Character bot, Trade trade) {
        if (entry.shoutTradeSelling) {
            Equip eq = entry.shoutTradeSellEquip;
            if (eq == null || bot.getInventory(InventoryType.EQUIP).getItem(eq.getPosition()) != eq) {
                return false; // piece sold/moved since we committed
            }
            Item staged = eq.copy();
            staged.setPosition((short) 1); // trade-window slot
            if (!trade.addItem(staged)) {
                return false;
            }
            InventoryManipulator.removeFromSlot(bot.getClient(), InventoryType.EQUIP,
                    eq.getPosition(), (short) 1, true);
            return true;
        }
        int price = entry.shoutTradeOffer.priceMeso();
        if (bot.getMeso() < price) {
            return false;
        }
        trade.setMeso(price); // debits the wallet now; refunded on cancel
        return true;
    }

    private static boolean partnerMeetsTerms(BotEntry entry, Character bot, Trade trade) {
        Trade partner = trade.getPartner();
        if (partner == null) {
            return false;
        }
        Offer o = entry.shoutTradeOffer;
        if (entry.shoutTradeSelling) {
            return partner.getStagedMeso() >= o.priceMeso(); // buyer must cover the ask
        }
        for (Item it : partner.getItems()) {
            if (it.getItemId() == o.itemId() && it instanceof Equip eq) {
                return BotScrollManager.equipBuyCeilingMeso(bot, eq) >= o.priceMeso(); // real per-roll WTP
            }
        }
        return false;
    }

    private static void finish(BotEntry entry, Character bot, long now) {
        Offer o = entry.shoutTradeOffer;
        if (entry.shoutTradeInitiator) { // exactly one side logs the clearing
            BotMarketLedger.getInstance().append(EventKind.TRADE, o.itemId(), 0, o.quantity(),
                    o.priceMeso(), bot.getId(), null, bot.getMapId());
        }
        BotMarketBook.of(entry, bot).observe(BotMarketMath.priceKey(o.itemId(), 0),
                o.priceMeso(), BotMarketMath.W_TRADE, now);
        BotManager.getInstance().botSay(bot, entry.shoutTradeSelling ? "thanks, pleasure doing business"
                : "thanks!");
        clear(entry);
    }

    private static void abort(BotEntry entry, Character bot) {
        if (bot.getTrade() != null) {
            Trade.cancelTrade(bot, Trade.TradeResult.NO_RESPONSE); // refunds any staged meso/item
        }
        clear(entry);
    }

    private static void clear(BotEntry entry) {
        // Drop the registered deal (initiator keyed it by the responder = our partner). A bot
        // responder already removed its own-keyed deal on claim; this covers finish/abort and the
        // bot<->player case where the player never claims.
        if (entry.shoutTradePartnerId != -1) {
            dealsByResponder.remove(entry.shoutTradePartnerId);
        }
        entry.shoutTradePartnerId = -1;
        entry.shoutTradeOffer = null;
        entry.shoutTradeSelling = false;
        entry.shoutTradeInitiator = false;
        entry.shoutTradeSellEquip = null;
        entry.shoutTradeInvited = false;
        entry.shoutTradeStaged = false;
        entry.shoutTradeLocked = false;
    }

    // ── emission (bot advertises a surplus equip) ─────────────────────────────

    private static void maybeEmitShout(BotEntry entry, Character bot, long now) {
        if (now < entry.nextShoutEmitMs) {
            return;
        }
        if (!BotFreeMarketManager.isFmMap(bot.getMapId()) || bot.getMap().getAllPlayers().size() < 2) {
            entry.nextShoutEmitMs = now + EMIT_MIN_MS; // not a social spot / no audience — check back later
            return;
        }
        BotPersonality p = entry.personality != null ? entry.personality : BotPersonality.defaults();
        entry.nextShoutEmitMs = now + BotManager.randMs(EMIT_MIN_MS, EMIT_MAX_MS);
        if (ThreadLocalRandom.current().nextDouble() > p.chattiness()) {
            return;
        }
        List<Equip> stock = BotInventoryManager.collectMarketableEquips(entry, bot);
        if (stock.isEmpty()) {
            return;
        }
        Equip eq = stock.get(0); // the top valuable surplus piece
        long ask = BotScrollManager.equipMarketQuote(entry, bot, eq).curveQuoteMeso();
        String name = ItemInformationProvider.getInstance().getName(eq.getItemId());
        if (ask <= 0 || name == null) {
            return;
        }
        ask = BotMarketMath.humanizeAsk(ask, bot.getId());
        Offer offer = new Offer(Kind.SELL, eq.getItemId(), 1, (int) Math.min(Integer.MAX_VALUE, ask));
        BotMarketShoutBus.getInstance().publish(bot.getMapId(), bot.getId(), offer, now);
        BotMarketLedger.getInstance().append(EventKind.SHOUT, offer.itemId(), 0, 1,
                offer.priceMeso(), bot.getId(), null, bot.getMapId());
        BotManager.getInstance().botSay(bot, BotMarketGrammar.format(Kind.SELL, name, 1, offer.priceMeso()));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static boolean isEquip(int itemId) {
        return itemId / 1_000_000 == 1;
    }

    private static Equip cleanEquip(int itemId) {
        Item base = ItemInformationProvider.getInstance().getEquipById(itemId);
        return base instanceof Equip eq ? eq : null;
    }
}
