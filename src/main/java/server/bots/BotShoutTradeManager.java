package server.bots;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * Shout-driven direct trades (living-economy S3 + the S4 haggle machine). Bots poll the
 * {@link BotMarketShoutBus} on their AI tick, match a heard {@code S>}/{@code B>} against what they
 * want or hold, and run a priced equip&lt;-&gt;meso swap over the vanilla {@link Trade}. Bots also EMIT
 * both sides: an {@code S>} for a surplus equip and a {@code B>} for the one upgrade they are shopping
 * for ({@link BotScrollManager#chooseBuyWant}), optionally with a roll criterion
 * ({@code B> 8+ att work glove 500k}) so better-than-clean demand is expressible.
 *
 * <p><b>Haggling (S4).</b> A trade no longer has to clear at the shouted number. A seller holding a
 * better-than-criteria piece counters ABOVE a {@code B>} (it invites, stages the piece, and names its
 * ask in trade chat); a buyer counters BELOW an {@code S>} it can't quite justify. Positions travel as
 * plain trade-chat lines — the counterpart parses the LAST meso token of a partner line
 * ({@link #onTradeChat}), so bots and humans speak the same wire format. Commitments are structural:
 * the seller's staged piece and the buyer's staged meso ({@link Trade#setMeso} is additive, so a
 * buyer's position can only ever rise), and the existing terms-met → confirm-beat → lock flow is the
 * acceptance step. Every position is clamped by the per-roll SSOTs — the buyer never crosses
 * {@link BotScrollManager#equipBuyCeilingMeso} of the ACTUAL staged piece, the seller never goes below
 * {@link BotFreeMarketManager#equipReservationUnit} — so a negotiation can only settle inside the real
 * deal zone. Concession pace/patience come from {@link BotPersonality} haggle traits.
 *
 * <p><b>v1 is EQUIP-scoped.</b> Equips carry real per-piece willingness-to-pay
 * ({@link BotScrollManager#equipBuyCeilingMeso}) and willingness-to-sell
 * ({@link BotScrollManager#equipMarketQuote}), so no parallel pricing is needed.
 * TODO(bot-stack-shout-trade): add quantity-aware consumable/stack trading. Every item and meso
 * movement rides {@code Trade}'s staged-debit + refund-on-cancel path, and each side locks only after
 * it has both staged its own side and validated the partner's, so a declined or timed-out window never
 * dupes or loses (invariant 5).
 */
public final class BotShoutTradeManager {

    /** A committed deal the initiator registers so the shout SPEAKER (the responder) recognizes the
     *  incoming invite as this negotiated swap rather than a random trade request. */
    private record Deal(int buyerId, int sellerId, Offer offer, long expiresAt) {}

    /** Keyed by RESPONDER (shout speaker) char id — the party that will receive the invite. */
    private static final Map<Integer, Deal> dealsByResponder = new ConcurrentHashMap<>();

    private static final long TRADE_DEADLINE_MS = 20_000L;   // give up on a stalled window
    private static final long HAGGLE_EXTEND_MS = 25_000L;    // each spoken position buys this much clock
    private static final long DEAL_TTL_MS = 15_000L;         // responder must be invited within this
    private static final int EMIT_MIN_MS = 4 * 60_000;       // opportunistic (browsing) shout cooldown
    private static final int EMIT_MAX_MS = 9 * 60_000;
    private static final int STAND_EMIT_MIN_MS = 20_000;     // fast cadence while standing to shout-sell
    private static final int STAND_EMIT_MAX_MS = 60_000;
    /** A counterparty whose position is beyond this multiple of ours isn't worth a window. */
    private static final int HAGGLE_GAP_CAP = 3;
    /** Trade-chat meso tokens below this are chatter ("a 9 att one"), not a price position. */
    private static final int MIN_SPOKEN_PRICE = 1_000;
    /** How long after its last {@code B>} a bot still accepts a walk-up seller's invite. */
    private static final long WALKUP_SELLER_WINDOW_MS = 3 * 60_000L;
    /** Recompute the standing buy want at most this often (the deep pass runs the repro DP). */
    private static final long BUY_WANT_TTL_MS = 10 * 60_000L;
    /** Fraction of the wallet a want may plan to spend. */
    private static final double BUY_WANT_WALLET_FRACTION = 0.5;

    private BotShoutTradeManager() {}

    static void tick(BotEntry entry, Character bot, boolean runAiTick) {
        long now = System.currentTimeMillis();
        if (entry.shoutTradeActive()) {
            driveTrade(entry, bot, now);
            return;
        }
        if (bot.getTrade() != null) {
            // An incoming invite may be a sibling's negotiated deal; else, while standing to
            // shout-sell, a walk-up buyer (human or bot) clicking to invite us for our ad — or,
            // while our B> is fresh, a walk-up seller bringing the piece we shouted for.
            // A human doesn't accept the popup the instant it appears — notice-and-click beat
            // first (SSOT: BotTradePacing; the owner/commander manual tick paces itself).
            if (stepBeat(entry, now)) {
                return;
            }
            if (!tryClaimResponder(entry, bot, now) && !tryAnswerWalkupBuyer(entry, bot, now)) {
                tryAnswerWalkupSeller(entry, bot, now);
            }
            return;
        }
        entry.shoutTradeStepAtMs = 0L; // no window — drop any beat armed for an invite that died
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

    /** One-shot human beat before the next trade-window step (accept the invite popup, drag the
     *  piece in / type the meso). Arms on first call, holds while running, clears when served —
     *  so each step pays exactly one {@link BotTradePacing#stepDelayMs()} pause. Returns true
     *  while the caller should wait. */
    private static boolean stepBeat(BotEntry entry, long now) {
        if (entry.shoutTradeStepAtMs == 0L) {
            entry.shoutTradeStepAtMs = now + BotTradePacing.stepDelayMs();
            return true;
        }
        if (now < entry.shoutTradeStepAtMs) {
            return true;
        }
        entry.shoutTradeStepAtMs = 0L;
        return false;
    }

    /** Bank a heard shout as the pending decision — the bot mulls it over for a couple seconds rather
     *  than pouncing the same tick (SoloMapling human-pacing borrow). One at a time. */
    private static void bankShoutDecision(BotEntry entry, int speakerId, Offer o, boolean selling,
                                          boolean counter, long now) {
        entry.shoutBuyDecideAtMs = now + BotManager.randMs(DELIBERATE_MIN_MS, DELIBERATE_MAX_MS);
        entry.shoutBuySpeakerId = speakerId;
        entry.shoutBuyOffer = o;
        entry.shoutBuySelling = selling;
        entry.shoutBuyCounter = counter;
    }

    private static void clearShoutDecision(BotEntry entry) {
        entry.shoutBuyDecideAtMs = 0L;
        entry.shoutBuySpeakerId = -1;
        entry.shoutBuyOffer = null;
        entry.shoutBuySelling = false;
        entry.shoutBuyCounter = false;
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
        boolean counter = entry.shoutBuyCounter;
        clearShoutDecision(entry); // consume the pending decision regardless of outcome
        if (o == null || bot.getMap() == null) {
            return true;
        }
        Character speaker = bot.getMap().getCharacterById(speakerId);
        if (speaker == null || speaker.getTrade() != null) {
            return true; // walked off / busy — the moment passed
        }
        if (selling && !counter) {
            Equip mine = findSellableEquip(entry, bot, o, now);
            if (mine == null || !BotMarketShoutBus.getInstance().claim(bot.getMapId(), speakerId, o)) {
                return true; // no longer have it, or someone else took the shout
            }
            commit(entry, bot, speaker, o, true, mine, now);
        } else if (selling) {
            // Counter-sell: my qualifying piece is better than the B> money — invite, stage it,
            // and name my ask in the window (design 8.5). Re-validate the piece + gap first.
            CounterSell cs = findCounterSellEquip(entry, bot, o, now);
            if (cs == null || !BotMarketShoutBus.getInstance().claim(bot.getMapId(), speakerId, o)) {
                return true;
            }
            commit(entry, bot, speaker, o, true, cs.piece(), now);
            armHaggle(entry, bot, cs.reservation(), cs.ask(), now);
            // Countering IS my reaction to the posted bid — don't re-act to it (or to the buyer
            // pre-staging that same number) as if it were a fresh concession.
            entry.haggleTheirPrice = o.priceMeso();
            entry.haggleTheirPriceSeen = o.priceMeso();
        } else if (!counter) {
            if (!plausibleBuy(bot, o) || !BotMarketShoutBus.getInstance().claim(bot.getMapId(), speakerId, o)) {
                return true; // no longer worth it, or claimed by another bot
            }
            commit(entry, bot, speaker, o, false, null, now);
        } else {
            // Counter-buy: the S> ask is above what I'd pay, but the gap is bridgeable — invite,
            // wait to SEE the piece (my bound is per-roll), then open below the ask.
            long bid = counterBuyOpening(entry, bot, o, now);
            if (bid <= 0
                    || !BotMarketShoutBus.getInstance().claim(bot.getMapId(), speakerId, o)) {
                return true;
            }
            commit(entry, bot, speaker, o, false, null, now);
            armHaggle(entry, bot, 0, bid, now);     // bid = my opening anchor; bound waits for the piece
            entry.haggleTheirPrice = o.priceMeso(); // their shout ask is their standing position...
            entry.haggleTheirPriceSeen = o.priceMeso(); // ...and my opening bid is my reaction to it
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
        for (BotMarketShoutBus.Shout s : shouts) {
            Offer o = s.offer();
            // A heard shout is an ADVERTISEMENT, not a clearing — it never moves the price belief.
            // Folding shouts in (W_SHOUT) let reproduction-cost asks echo between bots and poison
            // beliefs to billions; only realized trades/stall sales (W_TRADE) set the price now.
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
            if (o.kind() == Kind.SELL) {
                if (plausibleBuy(bot, o)) {
                    bankShoutDecision(entry, s.speakerId(), o, false, false, now); // I'd buy at ask
                    return true;
                }
                if (counterBuyOpening(entry, bot, o, now) > 0) {
                    bankShoutDecision(entry, s.speakerId(), o, false, true, now);  // I'd haggle it down
                    return true;
                }
            }
            if (o.kind() == Kind.BUY) {
                if (findSellableEquip(entry, bot, o, now) != null) {
                    bankShoutDecision(entry, s.speakerId(), o, true, false, now);  // I'd sell at their bid
                    return true;
                }
                if (findCounterSellEquip(entry, bot, o, now) != null) {
                    bankShoutDecision(entry, s.speakerId(), o, true, true, now);   // I'd counter above it
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

    /**
     * Opening bid for haggling a heard {@code S>} down: the shared bid SSOT at the clean band,
     * capped by wallet. Positive only when the ask is above the bid (else the fast path buys
     * at ask) yet within the bridgeable gap — a 5m ask against a 300k bid isn't worth a window.
     */
    private static long counterBuyOpening(BotEntry entry, Character bot, Offer o, long now) {
        Equip clean = cleanEquip(o.itemId());
        if (clean == null) {
            return 0;
        }
        long ceiling = Math.min(BotScrollManager.equipBuyCeilingMeso(bot, clean), bot.getMeso());
        if (ceiling <= 0) {
            return 0;
        }
        long bid = BotFreeMarketManager.equipUnitBid(BotMarketBook.of(entry, bot),
                o.itemId(), 0, ceiling, 0, now);
        if (bid <= 0 || o.priceMeso() <= bid || o.priceMeso() > bid * HAGGLE_GAP_CAP) {
            return 0;
        }
        return bid;
    }

    /** True when a rolled piece satisfies the offer's criterion (always true without one). The two
     *  dimensions are orthogonal and BOTH must hold: {@code clean} = never-scrolled (a failed-
     *  scroll copy with missing slots is not what "B> clean X" asked for), and the stat floor
     *  covers drop-roll variance ("8+ str clean steel knuckler" = an unscrolled piece that
     *  DROPPED well). */
    private static boolean meetsCriterion(Equip eq, Offer o) {
        BotMarketGrammar.Criterion c = o.criterion();
        if (c == null) {
            return true;
        }
        if (c.clean() && !BotScrollManager.isCleanRoll(ItemInformationProvider.getInstance(), eq)) {
            return false;
        }
        return c.stat() == null || c.stat().of(eq) >= c.min();
    }

    /** The cheapest marketable equip the bot holds matching the buy shout (item + roll criterion)
     *  whose reservation the offered price covers — sell the worst-rolled qualifying piece first.
     *  Null = nothing to sell at that price. Reservation is the shared FM ask SSOT
     *  (belief/salvage-based), not raw reproduction cost, so a bot will let illiquid/armor pieces
     *  go at what the market actually pays. */
    private static Equip findSellableEquip(BotEntry entry, Character bot, Offer o, long now) {
        Equip best = null;
        long bestValue = Long.MAX_VALUE;
        for (Equip eq : BotInventoryManager.collectMarketableEquips(entry, bot)) {
            if (eq.getItemId() != o.itemId() || !meetsCriterion(eq, o)) {
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

    private record CounterSell(Equip piece, long reservation, long ask) {}

    /**
     * A qualifying piece the posted {@code B>} money does NOT cover, worth countering above it:
     * cheapest-reserved piece meeting the criterion whose reservation exceeds the bid but whose
     * advertised ask stays inside the bridgeable gap. This is what makes criteria orders work —
     * without it, better-than-threshold inventory silently walks past every buy shout and the
     * criterion degenerates to worst-qualifying-roll-only.
     */
    private static CounterSell findCounterSellEquip(BotEntry entry, Character bot, Offer o, long now) {
        Equip best = null;
        long bestValue = Long.MAX_VALUE;
        for (Equip eq : BotInventoryManager.collectMarketableEquips(entry, bot)) {
            if (eq.getItemId() != o.itemId() || !meetsCriterion(eq, o)) {
                continue;
            }
            long value = reservationOf(entry, bot, eq, now);
            if (value > o.priceMeso() && value < bestValue) {
                best = eq;
                bestValue = value;
            }
        }
        if (best == null) {
            return null;
        }
        long ask = advertisedAsk(entry, bot, best, now);
        if (ask <= o.priceMeso() || ask > (long) o.priceMeso() * HAGGLE_GAP_CAP) {
            return null;
        }
        return new CounterSell(best, bestValue, BotMarketMath.humanizeAskRound(ask, bot.getId()));
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

    /** Enter negotiation mode: my hard bound (seller reservation / buyer per-roll WTP — 0 = set
     *  later, once the piece is visible), my opening position, and the personality patience. */
    private static void armHaggle(BotEntry entry, Character bot, long bound, long myOpen, long now) {
        BotPersonality p = entry.personality != null ? entry.personality : BotPersonality.defaults();
        entry.shoutTradeHaggle = true;
        entry.haggleBoundMeso = bound;
        entry.haggleMyPrice = (int) Math.min(Integer.MAX_VALUE, myOpen);
        entry.haggleRoundsLeft = p.haggleRounds();
        entry.haggleActAtMs = 0L;
        entry.haggleFinal = false;
        entry.shoutTradeDeadlineMs = Math.max(entry.shoutTradeDeadlineMs, now + HAGGLE_EXTEND_MS);
    }

    // ── trade-window chat (the haggle wire) ───────────────────────────────────

    private static final Pattern SPOKEN_MESO =
            Pattern.compile("(?i)" + BotChatManager.MESO_AMOUNT_TOKEN);

    /**
     * A partner trade-chat line reached this bot ({@code Trade.chat} hook via
     * {@link BotManager#onTradeChat}). The LAST meso token of a line is the speaker's price
     * position ("got a 9 att one, 700k" → 700k) — one wire format for bots and humans alike.
     * Small numbers are chatter, not positions ("a 9 att one"). Packet thread; the AI tick reads.
     */
    static void onTradeChat(BotEntry entry, Character speaker, String message) {
        if (!entry.shoutTradeActive() || speaker.getId() != entry.shoutTradePartnerId
                || message == null) {
            return;
        }
        int price = 0;
        Matcher m = SPOKEN_MESO.matcher(message);
        while (m.find()) {
            int v = BotChatManager.parseMesoAmount(m.group());
            if (v >= MIN_SPOKEN_PRICE) {
                price = v;
            }
        }
        if (price > 0) {
            entry.haggleTheirPrice = price;
        }
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
    private static boolean tryAnswerWalkupBuyer(BotEntry entry, Character bot, long now) {
        if (entry.pendingTradeCategory != null || !BotFreeMarketManager.isShoutStanding(entry)) {
            return false;
        }
        Trade trade = bot.getTrade();
        if (trade == null || trade.getPartner() == null || trade.getNumber() != 1) {
            return false; // incoming invite (slot 1) only
        }
        Character partner = trade.getPartner().getChr();
        Character commander = BotManager.getInstance().commanderOrOwner(entry);
        if (commander != null && partner.getId() == commander.getId()) {
            return false; // an owner/commander trade is the manual tick's business, not a sale
        }
        List<Equip> stock = BotInventoryManager.collectMarketableEquips(entry, bot);
        if (stock.isEmpty()) {
            return false; // nothing to sell after all — let the invite lapse
        }
        Equip eq = stock.get(0); // the very piece the stand advertises (top surplus)
        long ask = advertisedAsk(entry, bot, eq, now);
        if (ask <= 0) {
            return false;
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
        return true;
    }

    /**
     * The mirror of {@link #tryAnswerWalkupBuyer}: a walk-up SELLER (human who saw our {@code B>},
     * or anyone with the goods) invites the bot while its buy shout is still fresh. Accept as BUYER
     * in haggle-intent mode: the bot stages nothing until it can SEE the staged piece — its bound is
     * the per-roll WTP of that exact equip — then opens at its posted bid. A silent seller who just
     * stages a qualifying piece clears at the posted bid; one who names a higher price gets the
     * negotiation.
     */
    private static void tryAnswerWalkupSeller(BotEntry entry, Character bot, long now) {
        if (entry.pendingTradeCategory != null || entry.buyWant == null
                || now - entry.lastBuyShoutAtMs > WALKUP_SELLER_WINDOW_MS
                || bot.getMeso() < entry.buyWant.priceMeso()) {
            return;
        }
        Trade trade = bot.getTrade();
        if (trade == null || trade.getPartner() == null || trade.getNumber() != 1) {
            return; // incoming invite (slot 1) only
        }
        Character partner = trade.getPartner().getChr();
        Character commander = BotManager.getInstance().commanderOrOwner(entry);
        if (commander != null && partner.getId() == commander.getId()) {
            return; // owner/commander business belongs to the manual tick
        }
        entry.shoutTradePartnerId = partner.getId();
        entry.shoutTradeOffer = entry.buyWant;
        entry.shoutTradeSelling = false;
        entry.shoutTradeInitiator = true; // no sibling deal — this bot tapes the clearing
        entry.shoutTradeSellEquip = null;
        entry.shoutTradeInvited = true;   // window already exists
        entry.shoutTradeStaged = false;
        entry.shoutTradeDeadlineMs = now + TRADE_DEADLINE_MS;
        armHaggle(entry, bot, 0, 0, now); // bound + opening once the piece is staged
        Trade.visitTrade(bot, partner);   // accept → full window
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
            if (stepBeat(entry, now)) {
                return; // finding + dragging the piece / typing the meso takes a human a moment
            }
            if (!entry.shoutTradeSelling && entry.shoutTradeHaggle) {
                stageHagglingBuyer(entry, bot, trade, now); // waits to SEE the piece first
            } else if (stageMySide(entry, bot, trade)) {
                entry.shoutTradeStaged = true;
            } else {
                abort(entry, bot);
            }
            return;
        }
        maybeArmHaggle(entry, bot, now);
        if (entry.shoutTradeHaggle && driveHaggle(entry, bot, trade, now)) {
            return; // negotiating: thinking, spoke, or walked this tick
        }
        if (partnerMeetsTerms(entry, bot, trade)) {
            if (entry.shoutTradeConfirmAtMs == 0L) {
                entry.shoutTradeConfirmAtMs = now + BotTradePacing.confirmDelayMs(); // a human beat
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
        if (trade.isPartnerConfirmed() && !entry.shoutTradeHaggle) {
            abort(entry, bot); // partner locked terms that don't meet the deal
        }
        // While haggling, a partner locked below terms is a standing final position — the policy
        // (driveHaggle) accepts or walks on its own clock; the deadline is the backstop.
    }

    /** Lazily enter negotiation when a partner NAMES a price that contradicts the standing deal —
     *  a seller countering above our bid, or a buyer talking our ask down. This is how the fast
     *  accept-at-price paths discover, mid-window, that the other side wants to haggle. */
    private static void maybeArmHaggle(BotEntry entry, Character bot, long now) {
        if (entry.shoutTradeHaggle || entry.haggleTheirPrice <= 0) {
            return;
        }
        int mine = myPosition(entry);
        int theirs = entry.haggleTheirPrice;
        if (entry.shoutTradeSelling ? theirs >= mine : theirs <= mine) {
            return; // agreement chatter, not a counter
        }
        long bound = entry.shoutTradeSelling
                ? (entry.shoutTradeSellEquip == null ? 0
                        : reservationOf(entry, bot, entry.shoutTradeSellEquip, now))
                : buyerBound(entry, bot);
        if (bound <= 0) {
            return;
        }
        armHaggle(entry, bot, bound, mine, now);
    }

    /** Buyer's hard bound: per-roll WTP of the partner's ACTUAL staged piece, wallet-capped.
     *  0 while no qualifying piece is visible. */
    private static long buyerBound(BotEntry entry, Character bot) {
        Trade trade = bot.getTrade();
        Trade partner = trade == null ? null : trade.getPartner();
        if (partner == null) {
            return 0;
        }
        Offer o = entry.shoutTradeOffer;
        for (Item it : partner.getItems()) {
            if (it.getItemId() == o.itemId() && it instanceof Equip eq && meetsCriterion(eq, o)) {
                long spendable = bot.getMeso() + entry.haggleMyStagedMeso; // staged is already debited
                return Math.min(BotScrollManager.equipBuyCeilingMeso(bot, eq), spendable);
            }
        }
        return 0;
    }

    /** Haggling buyer's staging step: wait until the partner's piece is visible (the bound is
     *  per-roll), then stage the OPENING bid — staged meso is the buyer's binding position;
     *  {@code Trade.setMeso} is additive, so it can only ever rise from here. */
    private static void stageHagglingBuyer(BotEntry entry, Character bot, Trade trade, long now) {
        Trade partner = trade.getPartner();
        if (partner == null) {
            return;
        }
        Offer o = entry.shoutTradeOffer;
        Equip piece = null;
        for (Item it : partner.getItems()) {
            if (it.getItemId() == o.itemId() && it instanceof Equip eq) {
                piece = eq;
                break;
            }
        }
        if (piece == null) {
            return; // nothing staged yet — the deadline is the backstop
        }
        if (!meetsCriterion(piece, o)) {
            trade.chat(BotMarketChatter.walkAway());
            abort(entry, bot);
            return;
        }
        long bound = Math.min(BotScrollManager.equipBuyCeilingMeso(bot, piece), bot.getMeso());
        if (bound < MIN_SPOKEN_PRICE) {
            trade.chat(BotMarketChatter.walkAway()); // meets the letter of the criterion, worthless roll
            abort(entry, bot);
            return;
        }
        // Anchor: my computed opening bid when I came to talk an S> down (haggleMyPrice, set at
        // commit); else the price of my own B> the seller answered. NEVER the counterpart's ask.
        long anchor = entry.haggleMyPrice > 0 ? entry.haggleMyPrice : o.priceMeso();
        long open = Math.min(anchor, bound);
        open = Math.max(MIN_SPOKEN_PRICE, BotMarketMath.humanizeBidRound(open, bot.getId()));
        trade.setMeso((int) open); // additive escrow: debits now, refunds on cancel
        entry.haggleBoundMeso = bound;
        entry.haggleMyPrice = (int) open;
        entry.haggleMyStagedMeso = (int) open;
        entry.shoutTradeStaged = true;
        entry.shoutTradeDeadlineMs = Math.max(entry.shoutTradeDeadlineMs, now + HAGGLE_EXTEND_MS);
        if (entry.haggleTheirPrice > open) {
            trade.chat(BotMarketChatter.counterBuy((int) open));
        }
    }

    /**
     * One negotiation step. Runs only when the partner's position moved (or my scheduled "typing"
     * beat elapsed); every counter is styled (k/m), bounded (buyer never crosses the piece's WTP,
     * seller never its reservation), and spends a personality-limited round. Acceptance is
     * structural: conceding TO the partner's number makes the terms machinery lock. Returns true
     * while this tick belongs to the negotiation (thinking/spoke/walked).
     */
    private static boolean driveHaggle(BotEntry entry, Character bot, Trade trade, long now) {
        if (entry.shoutTradeAgreedPrice > 0) {
            return false; // settled — the terms machinery takes it from here
        }
        boolean selling = entry.shoutTradeSelling;
        int theirs = entry.haggleTheirPrice;
        if (selling) {
            Trade partner = trade.getPartner();
            int staged = partner == null ? 0 : partner.getStagedMeso();
            theirs = Math.max(theirs, staged); // a buyer's staged meso IS a binding position
        }
        if (theirs <= 0) {
            return false; // no counterpart position yet
        }
        int mine = myPosition(entry);
        if (selling ? theirs >= mine : theirs <= mine) {
            entry.shoutTradeAgreedPrice = mine; // they met my number — deal at my position
            return false;
        }
        if (theirs != entry.haggleTheirPriceSeen) {
            entry.haggleTheirPriceSeen = theirs;
            entry.haggleActAtMs = now + BotManager.randMs(2_500, 6_000); // read + think + type
            entry.shoutTradeDeadlineMs = Math.max(entry.shoutTradeDeadlineMs, now + HAGGLE_EXTEND_MS);
            return true;
        }
        if (entry.haggleActAtMs == 0L || now < entry.haggleActAtMs) {
            return entry.haggleActAtMs != 0L; // waiting out the beat / nothing new to act on
        }
        entry.haggleActAtMs = 0L;
        BotPersonality p = entry.personality != null ? entry.personality : BotPersonality.defaults();
        long bound = entry.haggleBoundMeso;
        boolean withinBound = selling
                ? BotMarketMath.acceptable(theirs, bound, p.haggleSlack())
                : BotMarketMath.acceptableAsk(theirs, bound, p.haggleSlack());
        boolean canTake = selling ? theirs >= bound : theirs <= bound;
        if (withinBound || (entry.haggleRoundsLeft <= 0 && canTake)) {
            acceptTheirPrice(entry, bot, trade, theirs);
            return true;
        }
        if (!canTake && (entry.haggleRoundsLeft <= 0 || entry.haggleFinal)) {
            trade.chat(BotMarketChatter.walkAway()); // provably outside my bound and out of patience
            abort(entry, bot);
            return true;
        }
        // Counter: concede a personality-scaled fraction of the gap, styled to a clean k/m.
        long counter = selling
                ? BotMarketMath.humanizeAskRound(
                        Math.round(BotMarketMath.counterPrice(mine, theirs, bound, p.haggleFirmness())),
                        bot.getId())
                : BotMarketMath.humanizeBidRound(
                        Math.round(BotMarketMath.counterBid(mine, theirs, bound, p.haggleFirmness())),
                        bot.getId());
        boolean crossed = selling ? counter <= theirs : counter >= theirs;
        if (crossed && canTake) {
            acceptTheirPrice(entry, bot, trade, theirs); // styling crossed their number — just take it
            return true;
        }
        boolean noMove = selling ? counter >= mine : counter <= mine;
        if (noMove) {
            if (!entry.haggleFinal) {
                entry.haggleFinal = true;
                trade.chat(BotMarketChatter.finalOffer(mine));
                entry.shoutTradeDeadlineMs = Math.max(entry.shoutTradeDeadlineMs, now + HAGGLE_EXTEND_MS);
            }
            return true; // said my piece; their move (or the deadline's)
        }
        entry.haggleRoundsLeft--;
        entry.haggleMyPrice = (int) Math.min(Integer.MAX_VALUE, counter);
        if (!selling) {
            topUpStagedMeso(entry, trade, entry.haggleMyPrice); // money where my mouth is
        }
        String line = entry.haggleRoundsLeft <= 0
                ? BotMarketChatter.finalOffer(entry.haggleMyPrice)
                : selling ? sellerCounterLine(entry, bot, entry.haggleMyPrice)
                          : BotMarketChatter.counterBuy(entry.haggleMyPrice);
        entry.haggleFinal = entry.haggleRoundsLeft <= 0;
        trade.chat(line);
        entry.shoutTradeDeadlineMs = Math.max(entry.shoutTradeDeadlineMs, now + HAGGLE_EXTEND_MS);
        return true;
    }

    /** Settle at the partner's number: buyer tops its escrow up to it; both sides record it as the
     *  agreed price and speak the agreement (the price token doubles as the wire signal). */
    private static void acceptTheirPrice(BotEntry entry, Character bot, Trade trade, int price) {
        if (!entry.shoutTradeSelling) {
            topUpStagedMeso(entry, trade, price);
        }
        entry.shoutTradeAgreedPrice = price;
        entry.haggleMyPrice = price;
        trade.chat(BotMarketChatter.agree(price));
    }

    /** Raise the buyer's staged meso to {@code target} ({@link Trade#setMeso} adds and debits the
     *  delta; escrow refunds it all on cancel). No-op if already there. */
    private static void topUpStagedMeso(BotEntry entry, Trade trade, int target) {
        int delta = target - entry.haggleMyStagedMeso;
        if (delta > 0) {
            trade.setMeso(delta);
            entry.haggleMyStagedMeso = target;
        }
    }

    /** The seller's counter line carries a stat preview of the staged piece — "got a +9 att work
     *  glove, 700k" — so the buyer (human or bot) sees WHY it costs more than the shout asked. */
    private static String sellerCounterLine(BotEntry entry, Character bot, int price) {
        Equip eq = entry.shoutTradeSellEquip;
        String preview = eq == null ? "it" : BotInventoryManager.describeAutoSellItem(
                ItemInformationProvider.getInstance(), null, eq);
        return BotMarketChatter.counterSell(preview, price);
    }

    /** My current binding position: the settled price, else my last counter, else the shout price. */
    private static int myPosition(BotEntry entry) {
        if (entry.shoutTradeAgreedPrice > 0) {
            return entry.shoutTradeAgreedPrice;
        }
        if (entry.haggleMyPrice > 0) {
            return entry.haggleMyPrice;
        }
        return entry.shoutTradeOffer.priceMeso();
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
            if (entry.shoutTradeHaggle) {
                // Countering a B>: the opening ask (with the piece's stat preview) IS the restate.
                trade.chat(sellerCounterLine(entry, bot, myPosition(entry)));
                return true;
            }
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
        entry.haggleMyStagedMeso = price;
        entry.haggleMyPrice = price;
        return true;
    }

    private static boolean partnerMeetsTerms(BotEntry entry, Character bot, Trade trade) {
        Trade partner = trade.getPartner();
        if (partner == null) {
            return false;
        }
        Offer o = entry.shoutTradeOffer;
        if (entry.shoutTradeSelling) {
            return partner.getStagedMeso() >= myPosition(entry); // buyer must cover my position
        }
        for (Item it : partner.getItems()) {
            if (it.getItemId() == o.itemId() && it instanceof Equip eq) {
                if (!meetsCriterion(eq, o)) {
                    continue; // staged junk beside (or instead of) the real piece
                }
                int payable = entry.haggleMyStagedMeso > 0 ? entry.haggleMyStagedMeso : o.priceMeso();
                if (BotScrollManager.equipBuyCeilingMeso(bot, eq) < payable) {
                    return false; // real per-roll WTP — this exact roll isn't worth what I'd pay
                }
                if (entry.shoutTradeHaggle && entry.shoutTradeAgreedPrice == 0
                        && entry.haggleTheirPrice > payable && !trade.isPartnerConfirmed()) {
                    return false; // seller still asking above my escrow and hasn't locked = no deal yet
                }
                entry.shoutTradeDealEquip = eq; // the piece the band observation prices at finish
                return true;
            }
        }
        return false;
    }

    private static void finish(BotEntry entry, Character bot, long now) {
        Offer o = entry.shoutTradeOffer;
        int price = myPosition(entry);
        Equip dealt = entry.shoutTradeDealEquip != null ? entry.shoutTradeDealEquip
                : entry.shoutTradeSellEquip;
        int band = dealt == null ? 0
                : BotScrollManager.equipQualityBand(ItemInformationProvider.getInstance(), dealt);
        if (entry.shoutTradeInitiator) { // exactly one side logs the clearing
            int sellerId = entry.shoutTradeSelling ? bot.getId() : entry.shoutTradePartnerId;
            int buyerId = entry.shoutTradeSelling ? entry.shoutTradePartnerId : bot.getId();
            BotMarketLedger.getInstance().append(EventKind.TRADE, o.itemId(), band, o.quantity(),
                    price, sellerId, buyerId, bot.getMapId());
        }
        // Clearings observe at the SETTLED price and the piece's ACTUAL band — a haggled godly-roll
        // sale must teach its own band's key, not pollute the clean one.
        BotMarketBook.of(entry, bot).observe(BotMarketMath.priceKey(o.itemId(), band),
                price, BotMarketMath.W_TRADE, now);
        BotManager.getInstance().botSay(bot, BotMarketChatter.thanks(entry.shoutTradeSelling));
        if (!entry.shoutTradeSelling) {
            // The want filled: wear the upgrade (which retires the want — the worn baseline moves)
            // and reset the no-fill ladder. Without this the bot would re-buy the same item forever.
            entry.buyWant = null;
            entry.buyWantNoFills = 0;
            entry.buyWantRecomputeAtMs = 0L;
            BotEquipManager.autoEquip(bot, BotManager.getInstance().commanderOrOwner(entry), null, true);
        }
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
        entry.shoutTradeStepAtMs = 0L;
        entry.shoutTradeHaggle = false;
        entry.haggleTheirPrice = 0;
        entry.haggleTheirPriceSeen = 0;
        entry.haggleMyPrice = 0;
        entry.haggleMyStagedMeso = 0;
        entry.haggleRoundsLeft = 0;
        entry.haggleBoundMeso = 0;
        entry.haggleFinal = false;
        entry.haggleActAtMs = 0L;
        entry.shoutTradeAgreedPrice = 0;
        entry.shoutTradeDealEquip = null;
    }

    // ── emission (bot advertises a surplus equip, or the upgrade it shops for) ──

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

    /** Most speaking windows sell; only this fraction may voice the standing buy want. A real FM
     *  reads sell-heavy — stock in hand demands a buyer NOW, while a want can wait — and a wall of
     *  {@code B>} lines reads as a bot market (owner feedback 2026-07-11). */
    private static final double BUY_SHOUT_CHANCE = 0.35;

    /** One speaking window: shout the top surplus equip (S&gt;), or occasionally the standing buy
     *  want (B&gt;) — players mix both sides of the market, but mostly hawk what they hold. The
     *  cooldown re-arms to a jittered {@code [min,max]} regardless of whether this window actually
     *  speaks (chattiness roll). */
    private static void emit(BotEntry entry, Character bot, long now, int minMs, int maxMs) {
        BotPersonality p = entry.personality != null ? entry.personality : BotPersonality.defaults();
        entry.nextShoutEmitMs = now + BotManager.randMs(minMs, maxMs);
        if (ThreadLocalRandom.current().nextDouble() > p.chattiness()) {
            return;
        }
        if (ThreadLocalRandom.current().nextDouble() < BUY_SHOUT_CHANCE) {
            if (emitBuyShout(entry, bot, now) || emitSellShout(entry, bot, now)) {
                return;
            }
        } else if (emitSellShout(entry, bot, now)) {
            return;
        }
        // No stock to sell and the buy roll failed: stay quiet — a silent browser is more
        // FM-real than every empty-bagged bot turning into a buy-order crier.
    }

    /** Shout the bot's top surplus equip. The spoken line carries a stat preview via the SAME
     *  formatter the sibling gear offers use ({@link BotInventoryManager#describeAutoSellItem},
     *  item-class perspective) so a shout reads like "S&gt; +7 att Maple Sword 5m" instead of a
     *  bare name — bots still match off the structured bus offer, so the preview is
     *  presentation-only. */
    private static boolean emitSellShout(BotEntry entry, Character bot, long now) {
        List<Equip> stock = BotInventoryManager.collectMarketableEquips(entry, bot);
        if (stock.isEmpty()) {
            return false;
        }
        Equip eq = stock.get(0); // the top valuable surplus piece
        long ask = advertisedAsk(entry, bot, eq, now);
        if (ask <= 0) {
            return false;
        }
        ask = BotMarketMath.humanizeAskRound(ask, bot.getId()); // shout prices read as clean k/m
        Offer offer = new Offer(Kind.SELL, eq.getItemId(), 1, (int) Math.min(Integer.MAX_VALUE, ask));
        BotMarketShoutBus.getInstance().publish(bot.getMapId(), bot.getId(), offer, now);
        BotMarketLedger.getInstance().append(EventKind.SHOUT, offer.itemId(), 0, 1,
                offer.priceMeso(), bot.getId(), null, bot.getMapId());
        String preview = BotInventoryManager.describeAutoSellItem(
                ItemInformationProvider.getInstance(), null, eq); // "+7 att <name>", item-class perspective
        BotManager.getInstance().botSay(bot, BotMarketChatter.sellShout(preview, offer.priceMeso(), bot.getId()));
        return true;
    }

    /**
     * Shout the bot's standing buy want as a {@code B>} — with a roll criterion when the upgrade
     * only exists above clean ({@code B> 8+ att work glove 500k}). The want itself is computed
     * demand ({@link BotScrollManager#chooseBuyWant}); the bid reprices each shout through the bid
     * SSOT so the private no-fill ladder walks it toward the WTP ceiling until somebody sells.
     */
    private static boolean emitBuyShout(BotEntry entry, Character bot, long now) {
        if (entry.buyWant == null || now >= entry.buyWantRecomputeAtMs) {
            BotScrollManager.BuyWant w = BotScrollManager.chooseBuyWant(entry, bot,
                    (long) (bot.getMeso() * BUY_WANT_WALLET_FRACTION), now);
            entry.buyWantRecomputeAtMs = now + BUY_WANT_TTL_MS;
            if (w == null) {
                entry.buyWant = null;
                return false;
            }
            if (entry.buyWant == null || entry.buyWant.itemId() != w.itemId()) {
                entry.buyWantNoFills = 0; // a new want starts its ladder from the bottom
            }
            entry.buyWantBand = w.band();
            entry.buyWantCeilingMeso = w.ceilingMeso();
            entry.buyWant = new Offer(Kind.BUY, w.itemId(), 1, 0, w.criterion());
        }
        Offer want = entry.buyWant;
        long ceiling = Math.min(entry.buyWantCeilingMeso, bot.getMeso());
        long bid = BotFreeMarketManager.equipUnitBid(BotMarketBook.of(entry, bot),
                want.itemId(), entry.buyWantBand, ceiling, entry.buyWantNoFills, now);
        bid = BotMarketMath.humanizeBidRound(bid, bot.getId());
        if (bid < MIN_SPOKEN_PRICE) {
            return false;
        }
        Offer offer = new Offer(Kind.BUY, want.itemId(), 1,
                (int) Math.min(Integer.MAX_VALUE, bid), want.criterion());
        entry.buyWant = offer; // keep the live bid on the want (walk-up sellers trade against it)
        BotMarketShoutBus.getInstance().publish(bot.getMapId(), bot.getId(), offer, now);
        BotMarketLedger.getInstance().append(EventKind.SHOUT, offer.itemId(), entry.buyWantBand, 1,
                offer.priceMeso(), null, bot.getId(), bot.getMapId());
        entry.lastBuyShoutAtMs = now;
        entry.buyWantNoFills++; // unfilled until proven otherwise; finish() resets on a fill
        String name = ItemInformationProvider.getInstance().getName(offer.itemId());
        String crit = "";
        if (offer.criterion() != null) {
            if (offer.criterion().stat() != null) {
                crit = offer.criterion().min() + "+ " + offer.criterion().stat().token() + " ";
            }
            if (offer.criterion().clean()) {
                crit += "clean ";
            }
        }
        String label = crit + (name != null ? name : "#" + offer.itemId());
        BotManager.getInstance().botSay(bot, BotMarketChatter.buyShout(label, offer.priceMeso(), bot.getId()));
        return true;
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
