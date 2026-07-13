package server.bots;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Small chatter pools + shout styling for a bot's market/trade lines, so its visible market talk
 * varies instead of repeating one fixed string (SoloMapling variety borrow, see
 * {@code docs/bot/kb/kb_bot_solomapling_trade_behavior.md}). Bots MATCH off the structured shout bus,
 * NOT these spoken lines, so styling is presentation-only and can drift freely. Per-bot-coherent where
 * it should read as one identity (a bot commits to one shout prefix, like {@code humanizeAsk} styles);
 * per-utterance-random where variety reads human (suffix, thanks, restate).
 */
final class BotMarketChatter {

    private BotMarketChatter() {}

    // Sell/buy shout prefixes. All stay parseable by BotMarketGrammar (letter immediately before '>',
    // except WTS>/WTB> where the grammar reads the LAST letter). S>/B> weighted heavier by repetition
    // so the plain form stays common.
    private static final String[] SELL_PREFIXES = {"S>", "S>", "S>", "SELL>", "Selling>", "WTS>"};
    private static final String[] BUY_PREFIXES = {"B>", "B>", "B>", "BUY>", "Buying>", "WTB>"};

    // Occasional trailing flavor on a shout (mostly none — the blanks keep it usually clean). Split by
    // kind so seller flavor ("cheap", "no lowball") never lands on a buy shout and vice versa.
    private static final List<String> SELL_SUFFIXES = List.of(
            "", "", "", "", "", "", "no lowball", "pm me", "cheap", "pros only", "cs ok", "quick sale");
    private static final List<String> BUY_SUFFIXES = List.of(
            "", "", "", "", "", "", "pm me", "have meso", "paying fair", "anyone?", "need it today",
            "w/ meso ready");

    private static final List<String> CONFIRM = List.of(
            "looks good, locking it in", "deal, confirming", "aight locking it in",
            "good to go, confirming", "sweet, locking it");
    private static final List<String> SELL_THANKS = List.of(
            "thanks, pleasure doing business", "ty, enjoy!", "thanks! gl with it",
            "pleasure doing business", "nice, thanks");
    private static final List<String> BUY_THANKS = List.of(
            "thanks!", "ty!", "appreciate it", "thanks, needed that", "cheers");
    // Restated in the trade window: %1$s = item name, %2$s = k/m price string.
    private static final List<String> RESTATE = List.of(
            "%1$s - %2$s", "%1$s, %2$s", "that's %2$s for the %1$s", "%1$s for %2$s", "%2$s for the %1$s");

    /** A styled sell-shout line: the bot's per-id prefix + stat-preview item + clean k/m price, with an
     *  occasional suffix and a rare full-uppercase — SoloMapling's shout-variety trick. {@code obnoxiousness}
     *  drives the {@code @@@} bubble padding (see {@link #padShout}). */
    static String sellShout(String preview, int price, int botId, double obnoxiousness) {
        String prefix = SELL_PREFIXES[Math.floorMod(botId, SELL_PREFIXES.length)];
        String suffix = pick(SELL_SUFFIXES);
        String line = prefix + " " + preview + " " + BotMarketGrammar.mesoShort(price)
                + (suffix.isEmpty() ? "" : " " + suffix);
        if (ThreadLocalRandom.current().nextInt(100) < 15) {
            line = line.toUpperCase(Locale.ROOT);
        }
        return padShout(line, obnoxiousness);
    }

    /** A styled buy-shout line: the bot's per-id prefix + item label + clean k/m price, with an
     *  occasional suffix and a rare full-uppercase — mirrors sellShout but for buy orders. */
    static String buyShout(String itemLabel, int price, int botId, double obnoxiousness) {
        String prefix = BUY_PREFIXES[Math.floorMod(botId, BUY_PREFIXES.length)];
        String suffix = pick(BUY_SUFFIXES);
        String line = prefix + " " + itemLabel + " " + BotMarketGrammar.mesoShort(price)
                + (suffix.isEmpty() ? "" : " " + suffix);
        if (ThreadLocalRandom.current().nextInt(100) < 15) {
            line = line.toUpperCase(Locale.ROOT);
        }
        return padShout(line, obnoxiousness);
    }

    /** General-chat length ceiling the client/server enforce (GeneralChatHandler: {@code > Byte.MAX_VALUE}
     *  is rejected). An obnoxious bot pads right up to it. */
    private static final int CHAT_MAX_LEN = Byte.MAX_VALUE; // 127
    /** Only bots at least this obnoxious bother padding at all. */
    private static final double PAD_MIN_OBNOX = 0.5;

    /**
     * Pad a shout with a trailing run of {@code @} to inflate the chat bubble — the real-player habit of
     * spamming filler so the balloon puffs up bigger and floats higher above the crowd. Only the
     * obnoxious do it, and how OFTEN (per-utterance roll) and how FAR toward the {@link #CHAT_MAX_LEN}
     * ceiling they push both scale with {@code obnoxiousness}. Kept ASCII (invariant 3) and never over
     * the length the server would reject.
     */
    private static String padShout(String line, double obnoxiousness) {
        if (obnoxiousness < PAD_MIN_OBNOX || line.length() + 2 >= CHAT_MAX_LEN
                || ThreadLocalRandom.current().nextDouble() > obnoxiousness) {
            return line;
        }
        // How close to the ceiling this one goes: a 0.5 bot pads to ~mid, a 1.0 bot slams the cap.
        int room = CHAT_MAX_LEN - line.length() - 1; // -1 for the separating space
        double reach = 0.4 + 0.6 * obnoxiousness;     // 0.4..1.0 of the remaining room
        int pad = Math.max(3, (int) Math.round(room * reach));
        pad = Math.min(pad, room);
        return line + " " + "@".repeat(pad);
    }

    static String confirm() {
        return pick(CONFIRM);
    }

    static String thanks(boolean selling) {
        return pick(selling ? SELL_THANKS : BUY_THANKS);
    }

    static String restate(String itemName, int price) {
        return String.format(Locale.ROOT, pick(RESTATE), itemName, BotMarketGrammar.mesoShort(price));
    }

    // ---- Haggle chains: buyer/seller price negotiation in trade window ----
    // CONTRACT: counterSell, counterBuy, finalOffer, agree must each contain the price exactly once
    // (parsed as the LAST meso token). walkAway must contain NO meso token at all.
    private static final List<String> COUNTER_SELL = List.of(
            "got a %1$s, how about %2$s", "this one is %1$s, id want %2$s",
            "mine beats that - %1$s for %2$s", "check it, %1$s. %2$s and its yours");
    private static final List<String> COUNTER_BUY = List.of(
            "hmm, %s?", "how about %s", "best i can do is %s", "i can go %s", "bit steep, %s?");
    private static final List<String> FINAL_OFFER = List.of(
            "%s, final offer", "cant go past %s", "%s, take it or leave it", "%s is my limit");
    private static final List<String> AGREE = List.of(
            "deal, %s it is", "alright, %s, deal", "ok %s works", "sold at %s");
    private static final List<String> WALK_AWAY = List.of(
            "too rich for me, sorry", "thats over my budget, gl selling", "cant do that price, sorry",
            "ill pass, thanks anyway", "out of my range, gl");

    /** A seller countering a buy shout with a better piece; uses per-item preview + final price ask. */
    static String counterSell(String preview, int price) {
        return String.format(Locale.ROOT, pick(COUNTER_SELL), preview, BotMarketGrammar.mesoShort(price));
    }

    /** A buyer countering a sell shout; price-only counter. */
    static String counterBuy(int price) {
        return String.format(Locale.ROOT, pick(COUNTER_BUY), BotMarketGrammar.mesoShort(price));
    }

    /** A final, take-it-or-leave-it offer; buyer or seller. */
    static String finalOffer(int price) {
        return String.format(Locale.ROOT, pick(FINAL_OFFER), BotMarketGrammar.mesoShort(price));
    }

    /** Agreement to a negotiated price. */
    static String agree(int price) {
        return String.format(Locale.ROOT, pick(AGREE), BotMarketGrammar.mesoShort(price));
    }

    /** Walk away from the negotiation; contains NO meso token. */
    static String walkAway() {
        return pick(WALK_AWAY);
    }

    // ---- Free-market trip narration (BotFreeMarketManager reply lines) ----
    // Per-situation pools so a bot's market-day patter varies instead of one fixed line per event.
    // Spoken flavor only — the trip decision (which reason fires) stays in BotFreeMarketManager.
    private static final List<String> TRIP_SERVICE = List.of(
            "gonna check on my shop at the fm", "time to tend my stall",
            "heading to the fm to mind my shop", "gotta go service my stall");
    private static final List<String> TRIP_FREDRICK = List.of(
            "gonna collect my earnings from fredrick", "time to grab my fredrick payout",
            "heading to fredrick for my proceeds", "gonna cash out at fredrick");
    private static final List<String> TRIP_SELL = List.of(
            "got some stuff to sell, heading to the free market", "loaded up, off to the fm to sell",
            "time to hawk some gear at the market", "heading to the fm, got things to move");
    private static final List<String> TRIP_WINDOW = List.of(
            "gonna go window-shop at the free market", "off to browse the fm for deals",
            "let's see what the market's got today", "gonna scope out the fm");
    private static final List<String> TRIP_FIZZLE = List.of(
            "market trip fizzled, heading out", "eh, this trip's a bust", "never mind, calling it off",
            "market run's not happening, heading out");
    private static final List<String> NO_ENTRANCE = List.of(
            "huh, no market entrance here, never mind", "no fm door around here, forget it",
            "can't find the market entrance, another time");
    private static final List<String> MARKET_PACKED = List.of(
            "market looks packed, another time", "fm's too crowded, i'll come back",
            "too busy in there, maybe later");
    private static final List<String> FREDRICK_DONE = List.of(
            "picked up my stall proceeds from fredrick", "got my payout from fredrick",
            "collected my earnings, nice", "fredrick paid out, sweet");
    private static final List<String> FREDRICK_PARTIAL = List.of(
            "fredricks still holding some of my stuff, no room in my bag",
            "bag's too full, fredrick's keeping the rest for now",
            "no space left, i'll grab the rest of fredrick's stash later");
    private static final List<String> SHOUT_STAND = List.of(
            "gonna hang around the market a bit, got some gear to sell",
            "i'll post up here and shout my wares for a while", "standing at the entrance to move some stock",
            "gonna chill at the fm and sell some gear");
    private static final List<String> PERMIT_BOUGHT = List.of(
            "bought a store permit, time to set up shop", "grabbed a permit, let's open up",
            "got my store permit, setting up now");
    private static final List<String> BARGAIN_UPGRADE = List.of(
            "found a gear upgrade at someone's shop", "nice, an upgrade for me here",
            "grabbed some gear that beats mine");
    private static final List<String> BARGAIN_DEAL = List.of(
            "grabbed a deal at someone's shop", "couldn't pass up this deal", "snagged a bargain here");
    // %d = count
    private static final List<String> STALL_OPENED = List.of(
            "shop's up, %d things listed", "open for business, %d items up", "stall's live, %d listings");
    private static final List<String> STALL_RESTOCKED = List.of(
            "restocked my shop, %d more up", "put out %d more items", "topped up the stall, %d added");

    static String tripService()     { return pick(TRIP_SERVICE); }
    static String tripFredrick()    { return pick(TRIP_FREDRICK); }
    static String tripSell()        { return pick(TRIP_SELL); }
    static String tripWindow()      { return pick(TRIP_WINDOW); }
    static String tripFizzled()     { return pick(TRIP_FIZZLE); }
    static String noEntrance()      { return pick(NO_ENTRANCE); }
    static String marketPacked()    { return pick(MARKET_PACKED); }
    static String fredrickCollected() { return pick(FREDRICK_DONE); }
    static String fredrickPartial() { return pick(FREDRICK_PARTIAL); }
    static String shoutStand()      { return pick(SHOUT_STAND); }
    static String permitBought()    { return pick(PERMIT_BOUGHT); }
    static String bargainBuy(boolean upgrade) { return pick(upgrade ? BARGAIN_UPGRADE : BARGAIN_DEAL); }
    static String stallOpened(int listed)   { return String.format(Locale.ROOT, pick(STALL_OPENED), listed); }
    static String stallRestocked(int more)  { return String.format(Locale.ROOT, pick(STALL_RESTOCKED), more); }

    private static String pick(List<String> pool) {
        return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    }
}
