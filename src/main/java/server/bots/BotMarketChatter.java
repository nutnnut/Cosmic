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

    // Sell/buy shout prefixes. All stay parseable by BotMarketGrammar (letter immediately before '>').
    // S>/B> weighted heavier by repetition so the plain form stays common.
    private static final String[] SELL_PREFIXES = {"S>", "S>", "S>", "SELL>", "Selling>"};
    private static final String[] BUY_PREFIXES = {"B>", "B>", "B>", "BUY>", "Buying>"};

    // Occasional trailing flavor on a shout (mostly none — the blanks keep it usually clean).
    private static final List<String> SHOUT_SUFFIXES = List.of(
            "", "", "", "", "", "", "no lowball", "pm me", "cheap", "pros only", "cs ok", "quick sale");

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
     *  occasional suffix and a rare full-uppercase — SoloMapling's shout-variety trick. */
    static String sellShout(String preview, int price, int botId) {
        String prefix = SELL_PREFIXES[Math.floorMod(botId, SELL_PREFIXES.length)];
        String suffix = pick(SHOUT_SUFFIXES);
        String line = prefix + " " + preview + " " + BotMarketGrammar.mesoShort(price)
                + (suffix.isEmpty() ? "" : " " + suffix);
        return ThreadLocalRandom.current().nextInt(100) < 15 ? line.toUpperCase(Locale.ROOT) : line;
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

    private static String pick(List<String> pool) {
        return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    }
}
