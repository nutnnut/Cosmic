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
 * examples (glove / ilbis / fish spear) are all equips. TODO(bot-stack-shout-trade): add
 * quantity-aware consumable/stack trading. Every item and meso movement rides {@code Trade}'s
 * staged-debit + refund-on-cancel path, and
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
    private static final int EMIT_MIN_MS = 4 * 60_000;       // opportunistic (browsing) shout cooldown
    private static final int EMIT_MAX_MS = 9 * 60_000;
    private static final int STAND_EMIT_MIN_MS = 20_000;     // fast cadence while standing to shout-sell
    private static final int STAND_EMIT_MAX_MS = 60_000;

    private BotShoutTradeManager() {}

    static void tick(BotEntry entry, Character bot, boolean runAiTick) {
        long now = System.currentTimeMillis();
        if (entry.shoutTradeActive()) {
            driveTrade(entry, bot, now);
            return;
        }
        if (bot.getTrade() != null) {
            // An incoming invite may be a sibling's negotiated deal; else, while standing to
            // shout-sell, a walk-up buyer (human or bot) clicking to invite us for our ad.
            if (!tryClaimResponder(entry, bot, now)) {
                tryAnswerWalkupBuyer(entry, bot, now);
            }
            return;
        }
        if (!runAiTick || entry.marketBusy) {
            return;
        }
        if (handlePendingShoutBuy(entry, bot, now)) {
            return; // deliberating over / acting on a shout we already noticed
        }
        if (tryMatchHeardShout(entry, bot, now)) {
            return;
        }
        maybeEmitShout(entry, bot, now);
    }

    private static final int DELIBERATE_MIN_MS = 2_000;  // "think about it" before acting on a shout
    private static final int DELIBERATE_MAX_MS = 6_000;

    /** Bank a heard shout as the pending decision — the bot mulls it over for a couple seconds rather
     *  than pouncing the same tick (SoloMapling human-pacing borrow). One at a time. */
    private static void bankShoutDecision(BotEntry entry, int speakerId, Offer o, boolean selling, long now) {
        entry.shoutBuyDecideAtMs = now + BotManager.randMs(DELIBERATE_MIN_MS, DELIBERATE_MAX_MS);
        entry.shoutBuySpeakerId = speakerId;
        entry.shoutBuyOffer = o;
        entry.shoutBuySelling = selling;
    }

    private static void clearShoutDecision(BotEntry entry) {
        entry.shoutBuyDecideAtMs = 0L;
        entry.shoutBuySpeakerId = -1;
        entry.shoutBuyOffer = null;
        entry.shoutBuySelling = false;
    }

    /** Drive a pending shout decision: keep mulling until the timer, then RE-VALIDATE (speaker still
     *  present, still want it, still affordable) and claim+commit; if it lapsed or was taken, drop it.
     *  Returns true while a decision is pending (consumes the tick so no new match is banked meanwhile). */
    private static boolean handlePendingShoutBuy(BotEntry entry, Character bot, long now) {
        if (entry.shoutBuyDecideAtMs == 0L) {
            return false;
        }
        if (now < entry.shoutBuyDecideAtMs) {
            return true; // still thinking it over
        }
        Offer o = entry.shoutBuyOffer;
        int speakerId = entry.shoutBuySpeakerId;
        boolean selling = entry.shoutBuySelling;
        clearShoutDecision(entry); // consume the pending decision regardless of outcome
        if (o == null || bot.getMap() == null) {
            return true;
        }
        Character speaker = bot.getMap().getCharacterById(speakerId);
        if (speaker == null || speaker.getTrade() != null) {
            return true; // walked off / busy — the moment passed
        }
        if (selling) {
            Equip mine = findSellableEquip(entry, bot, o, now);
            if (mine == null || !BotMarketShoutBus.getInstance().claim(bot.getMapId(), speakerId, o)) {
                return true; // no longer have it, or someone else took the shout
            }
            commit(entry, bot, speaker, o, true, mine, now);
        } else {
            if (!plausibleBuy(bot, o) || !BotMarketShoutBus.getInstance().claim(bot.getMapId(), speakerId, o)) {
                return true; // no longer worth it, or claimed by another bot
            }
            commit(entry, bot, speaker, o, false, null, now);
        }
        return true;
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
            // Don't pounce the instant a match is heard — bank it and mull it over for a couple
            // seconds (handlePendingShoutBuy claims + commits when the timer elapses). Leave the
            // shout on the bus during deliberation; the claim at commit still guarantees one buyer.
            if (o.kind() == Kind.SELL && plausibleBuy(bot, o)) {
                bankShoutDecision(entry, s.speakerId(), o, false, now); // I'd buy
                return true;
            }
            if (o.kind() == Kind.BUY && findSellableEquip(entry, bot, o, now) != null) {
                bankShoutDecision(entry, s.speakerId(), o, true, now); // I'd sell
                return true;
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

    /** The cheapest marketable equip the bot holds matching the buy shout whose reservation the
     *  offered price covers — sell the worst-rolled qualifying piece first. Null = nothing to sell.
     *  Reservation is the shared FM ask SSOT (belief/salvage-based), not raw reproduction cost, so a
     *  bot will now let illiquid/armor pieces go at what the market actually pays. */
    private static Equip findSellableEquip(BotEntry entry, Character bot, Offer o, long now) {
        Equip best = null;
        long bestValue = Long.MAX_VALUE;
        for (Equip eq : BotInventoryManager.collectMarketableEquips(entry, bot)) {
            if (eq.getItemId() != o.itemId()) {
                continue;
            }
            long value = reservationOf(entry, bot, eq, now);
            if (value > 0 && o.priceMeso() >= value && value < bestValue) {
                best = eq;
                bestValue = value;
            }
        }
        return best;
    }

    /** Advertised unit ask for a surplus piece, priced through the shared FM ask SSOT
     *  ({@link BotFreeMarketManager#equipUnitAsk}) instead of raw reproduction cost — market belief
     *  overrides remake cost downward, salvage floors it, never the 2.1b cap. 0 = unpriceable. */
    private static long advertisedAsk(BotEntry entry, Character bot, Equip eq, long now) {
        BotScrollManager.EquipQuote quote = BotScrollManager.equipMarketQuote(entry, bot, eq);
        if (quote == null || quote.curveQuoteMeso() <= 0) {
            return 0;
        }
        return BotFreeMarketManager.equipUnitAsk(BotMarketBook.of(entry, bot), quote, now);
    }

    /** The lowest unit price the bot accepts for a piece — the shared FM reservation (no seller
     *  margin). 0 = unpriceable. */
    private static long reservationOf(BotEntry entry, Character bot, Equip eq, long now) {
        BotScrollManager.EquipQuote quote = BotScrollManager.equipMarketQuote(entry, bot, eq);
        if (quote == null || quote.curveQuoteMeso() <= 0) {
            return 0;
        }
        return BotFreeMarketManager.equipReservationUnit(BotMarketBook.of(entry, bot), quote, now);
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

    private static boolean tryClaimResponder(BotEntry entry, Character bot, long now) {
        Trade trade = bot.getTrade();
        if (trade == null || trade.getPartner() == null || trade.getNumber() != 1) {
            return false; // not an incoming invite (slot 1) — leave it to the manual/peer trade tick
        }
        Deal d = dealsByResponder.get(bot.getId());
        if (d == null || d.expiresAt() <= now) {
            return false;
        }
        int otherId = d.sellerId() == bot.getId() ? d.buyerId() : d.sellerId();
        Character partner = trade.getPartner().getChr();
        if (partner.getId() != otherId) {
            return false; // invite from someone unrelated to our deal
        }
        boolean selling = d.sellerId() == bot.getId();
        Equip sell = selling ? findSellableEquip(entry, bot, d.offer(), now) : null;
        if (selling && sell == null) {
            return false; // no longer have the piece — let the invite lapse/cancel on the initiator's clock
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
        return true;
    }

    /**
     * A walk-up buyer (human or bot) clicked the bot to invite it while it stands at the FM entrance
     * advertising its wares (design: shout-sell state). If the bot is actively shout-standing and
     * still holds a marketable surplus piece, accept the invite as SELLER and offer that piece at its
     * current ask; {@link #driveTrade} then stages the equip and completes only once the buyer stages
     * at least the ask (its own escrow refunds anything on a decline/timeout — no dupe/loss). Owner /
     * commander invites are left to the manual-trade tick; a bot NOT standing to sell never auto-sells
     * to a random inviter.
     */
    private static void tryAnswerWalkupBuyer(BotEntry entry, Character bot, long now) {
        if (entry.pendingTradeCategory != null || !BotFreeMarketManager.isShoutStanding(entry)) {
            return;
        }
        Trade trade = bot.getTrade();
        if (trade == null || trade.getPartner() == null || trade.getNumber() != 1) {
            return; // incoming invite (slot 1) only
        }
        Character partner = trade.getPartner().getChr();
        Character commander = BotManager.getInstance().commanderOrOwner(entry);
        if (commander != null && partner.getId() == commander.getId()) {
            return; // an owner/commander trade is the manual tick's business, not a sale
        }
        List<Equip> stock = BotInventoryManager.collectMarketableEquips(entry, bot);
        if (stock.isEmpty()) {
            return; // nothing to sell after all — let the invite lapse
        }
        Equip eq = stock.get(0); // the very piece the stand advertises (top surplus)
        long ask = advertisedAsk(entry, bot, eq, now);
        if (ask <= 0) {
            return;
        }
        ask = BotMarketMath.humanizeAskRound(ask, bot.getId()); // clean k/m for the spoken price
        entry.shoutTradePartnerId = partner.getId();
        entry.shoutTradeOffer = new Offer(Kind.SELL, eq.getItemId(), 1, (int) Math.min(Integer.MAX_VALUE, ask));
        entry.shoutTradeSelling = true;
        entry.shoutTradeInitiator = true; // no sibling to log it — the bot tapes this clearing itself
        entry.shoutTradeSellEquip = eq;
        entry.shoutTradeInvited = true;   // window already exists
        entry.shoutTradeStaged = false;
        entry.shoutTradeDeadlineMs = now + TRADE_DEADLINE_MS;
        Trade.visitTrade(bot, partner); // accept → full window
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
            if (entry.shoutTradeConfirmAtMs == 0L) {
                entry.shoutTradeConfirmAtMs = now + BotManager.randMs(1_500, 3_000); // a human beat
                trade.chat(BotMarketChatter.confirm());
                return;
            }
            if (now < entry.shoutTradeConfirmAtMs) {
                return; // pause a moment before committing, like a person double-checking
            }
            Trade.completeTrade(bot); // locks my side; the exchange fires once both sides are locked
            entry.shoutTradeLocked = true;
            if (bot.getTrade() == null) {
                finish(entry, bot, now); // I was the second to lock — cleared this tick
            }
            return;
        }
        entry.shoutTradeConfirmAtMs = 0L; // terms slipped (partner un-staged) — restart the beat
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
                    eq.getPosition(), (short) 1, true); // trade.addItem broadcasts the piece to both windows
            // Restate the price in the trade window: the shout line scrolls away fast, so the buyer
            // needs the agreed number where they're about to type their meso.
            String name = ItemInformationProvider.getInstance().getName(eq.getItemId());
            trade.chat(BotMarketChatter.restate(name != null ? name : "this", entry.shoutTradeOffer.priceMeso()));
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
        BotManager.getInstance().botSay(bot, BotMarketChatter.thanks(entry.shoutTradeSelling));
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
        entry.shoutTradeConfirmAtMs = 0L;
    }

    // ── emission (bot advertises a surplus equip) ─────────────────────────────

    /** Opportunistic emission while the bot happens to be in an FM map with an audience (e.g. browsing
     *  a room). The deliberate entrance shout-stand ({@link #emitAtStand}) owns emission at its own
     *  fast cadence while active, so this stands down then. */
    private static void maybeEmitShout(BotEntry entry, Character bot, long now) {
        if (BotFreeMarketManager.isShoutStanding(entry)) {
            return; // the entrance shout-stand is driving emission at the fast cadence
        }
        if (now < entry.nextShoutEmitMs) {
            return;
        }
        // A social spot with an audience: an FM map, or a town the bot is resting in on a break
        // (owner: opportunistic shouts are welcome there too, not only at the market).
        boolean venue = BotFreeMarketManager.isFmMap(bot.getMapId())
                || BotBreakManager.onRestBreak(entry, bot, now);
        if (!venue || bot.getMap().getAllPlayers().size() < 2) {
            entry.nextShoutEmitMs = now + EMIT_MIN_MS; // not a social spot / no audience — check back later
            return;
        }
        emit(entry, bot, now, EMIT_MIN_MS, EMIT_MAX_MS);
    }

    /** Fast-cadence emission driven by the FM-entrance shout-stand state (design: a deliberate
     *  stand-still-and-advertise activity). Location + audience are guaranteed by the stand phase,
     *  so the FM-map/audience gates are skipped here. */
    static void emitAtStand(BotEntry entry, Character bot, long now) {
        if (now < entry.nextShoutEmitMs) {
            return;
        }
        emit(entry, bot, now, STAND_EMIT_MIN_MS, STAND_EMIT_MAX_MS);
    }

    /** Shout the bot's top surplus equip, re-arming the cooldown to a jittered {@code [min,max]}
     *  regardless of whether this window actually speaks (chattiness roll). The spoken line carries a
     *  stat preview via the SAME formatter the sibling gear offers use
     *  ({@link BotInventoryManager#describeAutoSellItem}, item-class perspective) so a shout reads
     *  like "S&gt; +7 att Maple Sword 5m" instead of a bare name — bots still match off the
     *  structured bus offer, so the preview is presentation-only. */
    private static void emit(BotEntry entry, Character bot, long now, int minMs, int maxMs) {
        BotPersonality p = entry.personality != null ? entry.personality : BotPersonality.defaults();
        entry.nextShoutEmitMs = now + BotManager.randMs(minMs, maxMs);
        if (ThreadLocalRandom.current().nextDouble() > p.chattiness()) {
            return;
        }
        List<Equip> stock = BotInventoryManager.collectMarketableEquips(entry, bot);
        if (stock.isEmpty()) {
            return;
        }
        Equip eq = stock.get(0); // the top valuable surplus piece
        long ask = advertisedAsk(entry, bot, eq, now);
        if (ask <= 0) {
            return;
        }
        ask = BotMarketMath.humanizeAskRound(ask, bot.getId()); // shout prices read as clean k/m
        Offer offer = new Offer(Kind.SELL, eq.getItemId(), 1, (int) Math.min(Integer.MAX_VALUE, ask));
        BotMarketShoutBus.getInstance().publish(bot.getMapId(), bot.getId(), offer, now);
        BotMarketLedger.getInstance().append(EventKind.SHOUT, offer.itemId(), 0, 1,
                offer.priceMeso(), bot.getId(), null, bot.getMapId());
        String preview = BotInventoryManager.describeAutoSellItem(
                ItemInformationProvider.getInstance(), null, eq); // "+7 att <name>", item-class perspective
        BotManager.getInstance().botSay(bot, BotMarketChatter.sellShout(preview, offer.priceMeso(), bot.getId()));
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
