---
name: kb_bot_market_shout_haggle
description: "B> buy-order shouts with roll criteria and the S4 in-window trade-chat haggle protocol: floor-priced criteria, spoken-position parsing, structural commitments, and why the bounds are always the per-roll SSOTs, never the shouted numbers"
metadata:
  node_type: memory
  type: project
---

# Bot market shout trading: B> criteria and haggling

`BotShoutTradeManager` emits both shout directions: `S>` for a surplus equip, `B>` for the one upgrade
a bot is shopping for (`BotScrollManager.chooseBuyWant`). Bot wants are CLEAN-ONLY (owner gate,
2026-07-11): clean means never-scrolled (`isCleanRoll`: all catalog upgrade slots still open — a
scroll always consumes one), NOT catalog stats — drop rolls vary a clean piece's stats within
`ItemInformationProvider.getRandStat`'s ±min(ceil(stat×0.1), 5) range, and only nonzero catalog stats
roll. A criterion therefore has two ORTHOGONAL dimensions that combine (`BotMarketGrammar.Criterion`:
stat floor + clean flag, stat aliases att/watk/wa, matt/ma/tma, str, dex, int, luk): `B> 8+ str clean
steel knuckler 500k` asks for an unscrolled piece that DROPPED well. Want selection scans the
drop-roll space capped at the real randomizer range, so a shouted criterion is always droppable. The
criterion prices the **minimum acceptable roll**, not a specific piece: a seller who answers the want
owes the *worst* qualifying item it holds, and the buyer's real protection is the per-roll
willingness-to-pay re-check at confirm time (`BotScrollManager.equipBuyCeilingMeso` on the ACTUAL
staged piece), not the number it shouted. Never read the shouted price as a promise about quality.

Bid pricing is `BotFreeMarketManager.equipUnitBid`: belief minus an opening margin, or — with no
belief — 0.6x the WTP ceiling (`OPEN_BID_CEILING_FRACTION`), deliberately erring low for the same
self-healing reason cold-start seeds do (see [[kb_bot_market_price_discovery]]: cheap opens clear and
probe upward on real evidence; expensive opens just starve). An unfilled want ladders the bid toward
the ceiling on each re-shout (`BID_LADDER_STEP`, capped rungs) but this ladder is **private per-bot
state** — an unfilled `B>` usually just means nobody present holds the item, which is much weaker
evidence than an unsold `S>` stall exposure, so it must never feed shared consensus.

**Haggling (S4).** A trade doesn't have to clear at the shouted number. A seller holding a
better-than-criteria piece counters ABOVE a `B>` (invites, stages the piece, names an ask in trade
chat with a stat preview); a buyer counters BELOW an `S>` it can't justify (invites, waits to see the
staged piece since its bound is the per-roll WTP of the actual roll, then opens below the ask).
Positions are spoken as plain trade-chat lines, not a special packet — `BotShoutTradeManager
.onTradeChat` parses the LAST meso token >= 1,000 (`MIN_SPOKEN_PRICE`) of a partner's line, so bots and
human players share one wire format.

Commitments are structural, not declarative: the seller's staged piece and the buyer's staged meso are
the position (`Trade.setMeso` is additive, so a buyer's stake can only rise within a window — there is
no "lower my offer"). Acceptance is structural too: conceding TO the partner's already-staged number is
just staging that number yourself, which lets the vanilla terms-met -> confirm-beat -> lock flow finish
the trade — there is no separate accept message. A human buyer haggles by staging meso; a human seller
by typing a number like "700k".

**Bounds are always the per-roll SSOTs, never the shouted or spoken numbers**: the buyer never crosses
`BotScrollManager.equipBuyCeilingMeso` of the piece actually staged, the seller never goes below
`BotFreeMarketManager.equipReservationUnit`. A negotiation can only settle inside that real zone —
the shout that started it and the counters along the way are advertisements, exactly like an `S>`/`B>`
shout or a stall ask; only the settled clearing feeds consensus, and it observes at the piece's ACTUAL
quality band (a haggled godly-roll sale must tape as godly, not as the band it was shouted at). Pace,
patience, and slack come from `BotPersonality` seed-derived haggle traits (`haggleFirmness`,
`haggleRounds`, `haggleSlack`); counters round to clean k/m (`BotMarketMath.humanizeAskRound` for
sellers, the floor-only `humanizeBidRound` for buyers — a buyer's rounding may never accidentally round
up past its ceiling). After 2-4 rounds a side accepts or walks; walking speaks a no-price line and
cancels the trade (escrow refunds, nothing is lost). A piece whose reservation the posted ask already
covers still clears at the posted price with no negotiation (fast path unchanged), and among qualifying
pieces the worst-qualifying roll is the one sold first.

See [[kb_bot_market_price_discovery]] for the shared consensus this trading sits on top of; nothing in
this file is a second, parallel pricing model — `equipBuyCeilingMeso` and `equipReservationUnit` are the
same per-roll SSOTs stall browsing and listing already use.
