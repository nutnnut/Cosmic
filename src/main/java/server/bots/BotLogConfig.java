package server.bots;

/**
 * SSOT for bot debug-logging toggles — the one place to find and flip which noisy bot log/report
 * lines are emitted. Each field names the exact console prefix it gates, so a line seen in the log
 * can be muted without hunting through the manager it lives in. Exposed live as the "log" group in
 * the /api/settings admin menu (BotWorldGraphWebServer) and reflected the same way as manager/combat.
 *
 * <p>The only bot debug-log toggle that lives elsewhere is the LLM chat trace
 * {@code BotLlmConfig.debugLog} (llm[...] lines) — it stays paired with the LLM enable/debug knobs in
 * the "LLM chat" admin panel, where debug implies enabled.
 */
public final class BotLogConfig {
    public static final class Config {
        // BotManager "Bot entry removed for charId ..." on disconnect/takeover cleanup. Routine and
        // spammy with many bots — off by default. (The untagged-path variant, a null-reason anomaly
        // with a stack trace, is always logged regardless; it flags a real bug, not routine churn.)
        public boolean ENTRY_REMOVED = false;

        // Per-item sold audit (bot-sell:), split by why the item left the bag:
        //  - forced-by-value: an item the bot would otherwise keep, liquidated under bag/value
        //    pressure (above-base equip shelf overflow, cramped USE shelf sales). The concerning case
        //    (a good roll liquidated) — but noisy with many bots, so off by default; flip when auditing.
        //  - whitelisted: routine junk the bot wants to sell anyway. Spammier still — off.
        public boolean SOLD_FORCED_BY_VALUE = false;
        public boolean SOLD_WHITELIST = false;

        // Cramped-bag "why isn't it selling" diagnostic (bot-sellblock:). Noisy; off by default.
        public boolean SELLBLOCK_CRAMPED = false;

        // Post-sell verify aid (bot CHAT, not server log): after a sell-trash visit, the bot says the
        // USE/ETC items it sold so the owner can spot a valuable being misclassified. Equips are
        // excluded (well tested). On by default — a handful of lines per shop trip, owner-facing.
        public boolean REPORT_SOLD_USE_ETC = true;

        // Console line per market-tape event (list/sale/trade/...) plus the fm[...] trip-phase traces
        // — the live debugging feed for the FM economy. The tape itself (bot_market_event) is always
        // written regardless. Off by default (noisy); flip on when digging into a trade.
        public boolean MARKET_TX_CONSOLE = false;
    }

    public static Config cfg = new Config();

    private BotLogConfig() {}
}
