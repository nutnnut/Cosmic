package server.bots;

import java.util.List;
import java.util.function.ToIntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import server.ItemInformationProvider;

/**
 * Pure, unit-testable parser + formatter for the market shout wire format (design sec 8.4):
 * <pre>
 *   S&gt; &lt;item&gt; [x&lt;qty&gt;] &lt;price&gt;              sell offer     S&gt; brown work glove 1m
 *   B&gt; &lt;item&gt; [x&lt;qty&gt;] &lt;price&gt;              buy offer      B&gt; ilbis 300k
 *   B&gt; &lt;min&gt;+ &lt;stat&gt; &lt;item&gt; [x&lt;qty&gt;] &lt;price&gt;  buy w/ criterion B&gt; 8+ att work glove 500k
 *   B&gt; clean &lt;item&gt; [x&lt;qty&gt;] &lt;price&gt;        buy w/ clean     B&gt; clean work glove 300k
 *   PC&gt; &lt;item&gt;                                price check    PC&gt; fish spear
 * </pre>
 * {@code <item>} is an exact item name (case-insensitive) or {@code #<itemId>}; {@code <price>} is a
 * meso token ({@code 1m}, {@code 900k}, {@code 1500000}). {@code S>}/{@code B>} also accept the
 * {@code WTS>}/{@code WTB>} shout prefixes humans type. Structure parsing is WZ-free — only NAME
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

    /** A stat threshold on a {@link Kind#BUY} offer (e.g. "8+ att"). {@code token()} is the canonical
     *  lowercase alias used when formatting; {@link #parseToken} accepts any recognized alias. */
    public enum Stat {
        ATT("att", "watk", "wa"),
        MATT("matt", "ma", "tma"),
        STR("str"),
        DEX("dex"),
        INT("int"),
        LUK("luk");

        private final String[] aliases;

        Stat(String... aliases) {
            this.aliases = aliases;
        }

        public String token() {
            return aliases[0];
        }

        /** This stat's value off a rolled equip. */
        public int of(client.inventory.Equip eq) {
            return switch (this) {
                case ATT -> eq.getWatk();
                case MATT -> eq.getMatk();
                case STR -> eq.getStr();
                case DEX -> eq.getDex();
                case INT -> eq.getInt();
                case LUK -> eq.getLuk();
            };
        }

        static Stat parseToken(String token) {
            if (token == null) {
                return null;
            }
            String t = token.toLowerCase(java.util.Locale.ROOT);
            for (Stat s : values()) {
                for (String alias : s.aliases) {
                    if (alias.equals(t)) {
                        return s;
                    }
                }
            }
            return null;
        }
    }

    /** A criterion attached to a {@link Kind#BUY} offer, along two orthogonal dimensions: a stat floor
     *  ({@code stat}/{@code min}) and cleanness ({@code clean}, never-scrolled). Both may be set at once
     *  — clean equips still drop with randomly rolled stats, so "clean AND 8+ str" is a meaningful want.
     *  {@code stat} may be null when only cleanness is meaningful; callers must null-check {@code stat}
     *  before calling {@link Stat#of}. At least one of the two dimensions is meaningful for any
     *  non-null Criterion. */
    public record Criterion(Stat stat, int min, boolean clean) {
        public Criterion(Stat stat, int min) {
            this(stat, min, false);
        }
    }

    /** Sentinel "clean" (unscrolled), no-stat-floor criterion; {@link Criterion#stat} is null,
     *  {@link Criterion#min} unused. */
    public static final Criterion CLEAN = new Criterion(null, 0, true);

    /** A parsed, resolved shout. {@code priceMeso} is 0 for {@link Kind#PRICE_CHECK}. {@code criterion}
     *  is non-null only for a {@link Kind#BUY} offer that named a stat threshold or "clean". */
    public record Offer(Kind kind, int itemId, int quantity, int priceMeso, Criterion criterion) {
        public Offer(Kind kind, int itemId, int quantity, int priceMeso) {
            this(kind, itemId, quantity, priceMeso, null);
        }
    }

    // Accept the styled prefixes bots emit and humans type — S>/SELL>/Selling>/WTS> and
    // B>/BUY>/Buying>/WTB> (the leading letter decides the kind, except wts/wtb where the LAST
    // letter does — see kind resolution in parse()), plus PC>/PRICE>.
    private static final Pattern OFFER = Pattern.compile(
            "(?i)^\\s*(s(?:ell(?:ing)?)?|b(?:uy(?:ing)?)?|wts|wtb)>\\s*(.+?)(?:\\s+x\\s*(\\d+))?\\s+("
                    + BotChatManager.MESO_AMOUNT_TOKEN + ")\\s*$");
    private static final Pattern PRICE_CHECK = Pattern.compile("(?i)^\\s*(?:pc|price)>\\s*(.+?)\\s*$");

    // Leading "<min>+ <stat>" on the item-name text of a BUY offer, e.g. "8+ att work glove" or
    // "8 watk work glove". Stripped from the name before resolution; never applied to S> lines.
    private static final Pattern CRITERION_PREFIX = Pattern.compile(
            "(?i)^(\\d+)\\s*\\+?\\s*(" + statAliasPattern() + ")\\s+(.+)$");

    // Leading "clean" word on the item-name text, e.g. "clean work glove". Stripped from the name
    // before resolution on both B> and S> lines; requires trailing name text so "S> clean 300k"
    // (name would become empty) falls through to name resolution unchanged, as today.
    private static final Pattern CLEAN_PREFIX = Pattern.compile("(?i)^clean\\s+(.+)$");

    private static String statAliasPattern() {
        StringBuilder sb = new StringBuilder();
        for (Stat s : Stat.values()) {
            for (String alias : s.aliases) {
                if (sb.length() > 0) {
                    sb.append('|');
                }
                sb.append(alias);
            }
        }
        return sb.toString();
    }

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
            case "s", "sell", "selling", "b", "buy", "buying", "wts", "wtb", "pc", "price" -> true;
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
        String prefixToken = m.group(1).toLowerCase(java.util.Locale.ROOT);
        Kind kind = prefixToken.startsWith("wt")
                ? (prefixToken.charAt(prefixToken.length() - 1) == 's' ? Kind.SELL : Kind.BUY)
                : (prefixToken.charAt(0) == 's' ? Kind.SELL : Kind.BUY);
        String nameText = m.group(2).trim();
        Criterion criterion = null;
        if (kind == Kind.BUY) {
            // "<n>+ <stat>" and "clean" may appear in either order ("8+ str clean X" or
            // "clean 8+ str X"); strip whichever prefix matches, up to once each.
            Stat stat = null;
            int min = 0;
            boolean clean = false;
            for (int pass = 0; pass < 2; pass++) {
                if (!clean) {
                    Matcher cleanM = CLEAN_PREFIX.matcher(nameText);
                    if (cleanM.matches()) {
                        clean = true;
                        nameText = cleanM.group(1).trim();
                        continue;
                    }
                }
                if (stat == null) {
                    Matcher cm = CRITERION_PREFIX.matcher(nameText);
                    if (cm.matches()) {
                        int parsedMin = parseInt(cm.group(1));
                        Stat parsedStat = Stat.parseToken(cm.group(2));
                        if (parsedStat != null && parsedMin >= 1) {
                            stat = parsedStat;
                            min = parsedMin;
                            nameText = cm.group(3).trim();
                            continue;
                        }
                    }
                }
                break;
            }
            if (stat != null || clean) {
                criterion = new Criterion(stat, min, clean);
            }
        } else if (kind == Kind.SELL) {
            Matcher cleanM = CLEAN_PREFIX.matcher(nameText);
            if (cleanM.matches()) {
                nameText = cleanM.group(1).trim();
            }
        }
        int id = resolveItem(nameText);
        if (id <= 0) {
            return null;
        }
        int qty = m.group(3) != null ? Math.max(1, parseInt(m.group(3))) : 1;
        int price = BotChatManager.parseMesoAmount(m.group(4));
        if (price <= 0) {
            return null;
        }
        return new Offer(kind, id, qty, price, criterion);
    }

    /** The spoken/wire line for an offer (US-ASCII). Caller supplies the item's display name (keeps
     *  this WZ-free). Round-trips through {@link #parse} when the name resolves uniquely. */
    public static String format(Kind kind, String itemName, int quantity, int priceMeso) {
        return format(kind, null, itemName, quantity, priceMeso);
    }

    /** Like {@link #format(Kind, String, int, int)} but with an optional stat {@link Criterion}
     *  (only meaningful for {@link Kind#BUY}; emits e.g. {@code B> 8+ att work glove 500k}). */
    public static String format(Kind kind, Criterion criterion, String itemName, int quantity, int priceMeso) {
        String prefix = switch (kind) {
            case SELL -> "S> ";
            case BUY -> "B> ";
            case PRICE_CHECK -> "PC> ";
        };
        if (kind == Kind.PRICE_CHECK) {
            return prefix + itemName;
        }
        String crit = "";
        if (criterion != null) {
            if (criterion.stat() != null) {
                crit += criterion.min() + "+ " + criterion.stat().token() + " ";
            }
            if (criterion.clean()) {
                crit += "clean ";
            }
        }
        String qty = quantity > 1 ? " x" + quantity : "";
        return prefix + crit + itemName + qty + " " + mesoShort(priceMeso);
    }

    /** Compact meso string a human reads and {@link BotChatManager#parseMesoAmount} accepts back. */
    static String mesoShort(int meso) {
        // Millions scale: keep up to 3 significant decimals (round-to-thousand) so no digit is lost
        // (92_500_000 -> "92.5m", 1_250_000 -> "1.25m", 1_200_000 -> "1.2m"). Non-round tails fall through.
        if (meso >= 1_000_000 && meso % 1_000 == 0) {
            int frac = (meso % 1_000_000) / 1_000; // 0..999 thousandths of a million
            if (frac == 0) {
                return (meso / 1_000_000) + "m";
            }
            String decimals = String.format("%03d", frac).replaceAll("0+$", "");
            return (meso / 1_000_000) + "." + decimals + "m";
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
