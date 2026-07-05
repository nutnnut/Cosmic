package server.bots;

import java.util.List;
import java.util.function.ToIntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import server.ItemInformationProvider;

/**
 * Pure, unit-testable parser + formatter for the market shout wire format (design sec 8.4):
 * <pre>
 *   S&gt; &lt;item&gt; [x&lt;qty&gt;] &lt;price&gt;   sell offer     S&gt; brown work glove 1m
 *   B&gt; &lt;item&gt; [x&lt;qty&gt;] &lt;price&gt;   buy offer      B&gt; ilbis 300k
 *   PC&gt; &lt;item&gt;                   price check    PC&gt; fish spear
 * </pre>
 * {@code <item>} is an exact item name (case-insensitive) or {@code #<itemId>}; {@code <price>} is a
 * meso token ({@code 1m}, {@code 900k}, {@code 1500000}). Structure parsing is WZ-free — only NAME
 * resolution touches item data, and it's a swappable seam ({@link #nameResolver}) so tests inject a
 * fake catalog. Name resolution reuses the same {@link ItemInformationProvider#getItemDataByName}
 * search the farm/trade commands use, and the meso token + parse reuse {@link BotChatManager} (SSOT).
 *
 * <p>Players PARSE inbound chat into an {@link Offer}; bots FORMAT an Offer into a spoken line — both
 * species converge on the shout bus as the same structured object, so the grammar is the single wire
 * format either way (design resolution 6). All tokens are US-ASCII (invariant 3).
 */
public final class BotMarketGrammar {

    public enum Kind { SELL, BUY, PRICE_CHECK }

    /** A parsed, resolved shout. {@code priceMeso} is 0 for {@link Kind#PRICE_CHECK}. */
    public record Offer(Kind kind, int itemId, int quantity, int priceMeso) {}

    // Accept the styled prefixes bots emit and humans type — S>/SELL>/Selling> and B>/BUY>/Buying>
    // (the leading letter still decides the kind), plus PC>/PRICE>.
    private static final Pattern OFFER = Pattern.compile(
            "(?i)^\\s*(s(?:ell(?:ing)?)?|b(?:uy(?:ing)?)?)>\\s*(.+?)(?:\\s+x\\s*(\\d+))?\\s+("
                    + BotChatManager.MESO_AMOUNT_TOKEN + ")\\s*$");
    private static final Pattern PRICE_CHECK = Pattern.compile("(?i)^\\s*(?:pc|price)>\\s*(.+?)\\s*$");

    /** Item NAME -&gt; itemId, or -1 if unresolved/ambiguous. Default: exact-match-wins over the same
     *  ItemInformationProvider search the farm/trade commands use; tests swap this. */
    static ToIntFunction<String> nameResolver = BotMarketGrammar::resolveByName;

    private BotMarketGrammar() {}

    /** Cheap prefix pre-check before the full regex, for the chat hot path. */
    public static boolean looksLikeShout(String line) {
        if (line == null) {
            return false;
        }
        String s = line.stripLeading();
        int gt = s.indexOf('>');
        if (gt <= 0 || gt > 8) {
            return false; // a shout prefix is short; anything else isn't one
        }
        return switch (s.substring(0, gt).toLowerCase(java.util.Locale.ROOT)) {
            case "s", "sell", "selling", "b", "buy", "buying", "pc", "price" -> true;
            default -> false;
        };
    }

    /** Parse + resolve one line into an {@link Offer}, or null if it isn't a well-formed shout. */
    public static Offer parse(String line) {
        if (line == null) {
            return null;
        }
        Matcher pc = PRICE_CHECK.matcher(line);
        if (pc.matches()) {
            int id = resolveItem(pc.group(1).trim());
            return id > 0 ? new Offer(Kind.PRICE_CHECK, id, 1, 0) : null;
        }
        Matcher m = OFFER.matcher(line);
        if (!m.matches()) {
            return null;
        }
        int id = resolveItem(m.group(2).trim());
        if (id <= 0) {
            return null;
        }
        int qty = m.group(3) != null ? Math.max(1, parseInt(m.group(3))) : 1;
        int price = BotChatManager.parseMesoAmount(m.group(4));
        if (price <= 0) {
            return null;
        }
        Kind kind = Character.toLowerCase(m.group(1).charAt(0)) == 's' ? Kind.SELL : Kind.BUY;
        return new Offer(kind, id, qty, price);
    }

    /** The spoken/wire line for an offer (US-ASCII). Caller supplies the item's display name (keeps
     *  this WZ-free). Round-trips through {@link #parse} when the name resolves uniquely. */
    public static String format(Kind kind, String itemName, int quantity, int priceMeso) {
        String prefix = switch (kind) {
            case SELL -> "S> ";
            case BUY -> "B> ";
            case PRICE_CHECK -> "PC> ";
        };
        if (kind == Kind.PRICE_CHECK) {
            return prefix + itemName;
        }
        String qty = quantity > 1 ? " x" + quantity : "";
        return prefix + itemName + qty + " " + mesoShort(priceMeso);
    }

    /** Compact meso string a human reads and {@link BotChatManager#parseMesoAmount} accepts back. */
    static String mesoShort(int meso) {
        if (meso >= 1_000_000 && meso % 1_000_000 == 0) {
            return (meso / 1_000_000) + "m";
        }
        if (meso >= 1_000 && meso % 1_000 == 0) {
            return (meso / 1_000) + "k";
        }
        return Integer.toString(meso);
    }

    private static int resolveItem(String token) {
        if (token.isEmpty()) {
            return -1;
        }
        if (token.charAt(0) == '#') {
            return parseInt(token.substring(1).trim());
        }
        return nameResolver.applyAsInt(token);
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int resolveByName(String name) {
        List<tools.Pair<Integer, String>> matches =
                ItemInformationProvider.getInstance().getItemDataByName(name);
        if (matches.isEmpty()) {
            return -1;
        }
        for (tools.Pair<Integer, String> m : matches) {
            if (m.getRight().equalsIgnoreCase(name)) {
                return m.getLeft(); // exact name wins even amid substring matches
            }
        }
        return matches.size() == 1 ? matches.get(0).getLeft() : -1; // ambiguous -> unresolved
    }
}
