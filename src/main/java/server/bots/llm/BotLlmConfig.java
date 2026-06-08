package server.bots.llm;

public final class BotLlmConfig {
    public static volatile boolean enabled = false;
    // LLM-improvised idle banter between an owner's own bots (gated separately from owner-chat replies
    // because it's autonomous — it spends API tokens without the player initiating).
    public static volatile boolean banterEnabled = true;
    public static volatile boolean typoSuggesterEnabled = false; // recommended off if LLM on, too many false positive and block LLM chat sometimes

    // Which backend serves bot replies. OLLAMA = local model (free, private, CPU-bound);
    // ANTHROPIC = Claude cloud API (better quality, costs per message, needs API key + network).
    public enum Provider { OLLAMA, ANTHROPIC }
    public static volatile Provider provider = Provider.OLLAMA;

    // --- Ollama (local) backend, used when provider == OLLAMA ---
    public static volatile String endpoint = "http://localhost:11434";
    public static volatile String model = "gemma4:e2b";

    // --- Anthropic (Claude) backend, used when provider == ANTHROPIC ---
    // API key is read from the ANTHROPIC_API_KEY env var at startup — set it in the
    // container environment (docker-compose), never commit it. Can be overridden at runtime.
    public static volatile String anthropicApiKey = System.getenv("ANTHROPIC_API_KEY");
    public static volatile String anthropicEndpoint = "https://api.anthropic.com/v1/messages";
    public static volatile String anthropicVersion = "2023-06-01";
    // Haiku 4.5 is the cheapest/fastest Claude model — the right fit for short MMO chatter.
    // Swap to claude-sonnet-4-6 / claude-opus-4-8 for higher quality at higher cost.
    public static volatile String anthropicModel = "claude-haiku-4-5";
    // Mark the reused per-bot system prompt as cacheable. NOTE: Claude only caches prefixes
    // over ~4096 tokens (Haiku/Opus), so short persona prompts won't actually cache yet —
    // this is wired for when the system prompt grows (lore, long persona, few-shot examples).
    public static volatile boolean anthropicPromptCaching = true;

    public static volatile int requestTimeoutMs = 30_000;
    public static volatile int maxConcurrentGlobal = 4;
    // Hard ceiling on the FULL reply (sum across split messages). Computed
    // dynamically as maxReplyMessages * maxReplyCharsPerMessage so changing
    // either knob below auto-resizes the cap.
    public static int maxReplyChars() {
        return Math.max(1, maxReplyMessages) * Math.max(1, maxReplyCharsPerMessage);
    }

    // CPU cap: how many threads Ollama may use per inference. 0 = let Ollama
    // decide (uses all cores → 100% spike). Default to half the available
    // logical cores so the game server keeps room to breathe.
    public static volatile int numThreads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);

    // Keep the model resident in Ollama for this long after each call so the
    // next reply doesn't pay a cold-load cost. Examples: "5m", "30m", "-1" (forever).
    public static volatile String keepAlive = "30m";

    // Speed knobs for CPU-only hosts. All can be tuned at runtime.

    // Qwen3/3.5 emit <think>...</think> tokens by default — pure waste for
    // one-line chat. Disabling roughly halves wall time on short replies.
    public static volatile boolean disableThinking = true;

    // Hard cap on tokens generated. Reply is split across up to
    // maxReplyMessages chat messages of maxReplyCharsPerMessage chars each,
    // so size this to roughly cover the multi-message budget plus a bit.
    public static volatile int maxPredictTokens = 24;

    // Context window size. Our prompts stay under ~300 tokens; the default
    // 4096 wastes memory bandwidth. Lower = faster on CPU.
    public static volatile int numCtx = 2048;

    // How many recent turns from memory to inline into the prompt. Lower =
    // shorter prompt = faster prompt eval on CPU. Long-term context still
    // survives via the compacted .summary.txt.
    public static volatile int recentTurnsInPrompt = 5;

    // Gemma4 official preset is temp=1.0 / top_p=0.95 / top_k=64 — tuned for general
    // assistant use. For very short MMO chatter we tighten the tail slightly so the
    // long-tail "weird picks" that hurt 50-token replies get clipped. Keep temperature
    // at 1.0 since instruction-tuning was done there.
    public static volatile double temperature = 1.0;
    public static volatile double topP = 0.9;
    public static volatile int topK = 40;
    public static volatile double minP = 0.0;
    // presence_penalty was inherited from the Qwen3.5 non-thinking preset; Gemma doesn't
    // recommend it and >0 on short replies causes "talks-around-the-word" outputs because
    // common tokens (you/the/it) get pushed away after their first use.
    public static volatile double presencePenalty = 0.0;
    public static volatile double repeatPenalty = 1.0;

    // Multi-message reply: long replies are split on sentence boundaries and
    // sent as up to maxReplyMessages chat messages with multiMessageDelayMs
    // between each. Set to 1 to disable splitting.
    public static volatile int maxReplyMessages = 2;
    public static volatile int multiMessageDelayMs = 1800;
    // Per-message char cap. The v83 chat balloon truncates long lines, so this must stay at/under the
    // in-game display limit (~80) — otherwise a single reply gets cut off instead of split. splitForChat
    // breaks anything longer onto a second message.
    public static volatile int maxReplyCharsPerMessage = 80;

    // When true, every LLM call prints the full prompt + raw response + timing
    // to the server log. Use to diagnose nonsense / sampling issues, then turn off.
    public static volatile boolean debugLog = true;

    public static volatile String memoryDir = "bots/llm-memory";
    // Compaction is gap-free + history-preserving. The jsonl is append-only forever.
    // A cursor sidecar tracks how many leading turns have been folded into the rolling
    // summary. Compaction fires when (total - cursor) > recentTurnsInPrompt + compactBatchSize:
    // the oldest compactBatchSize turns get summarized into summary.txt and the cursor
    // advances. Bigger batch = fewer LLM calls per chat-active bot but more verbatim
    // turns in the prompt. ONE LLM compact call per compactBatchSize turns.
    public static volatile int compactBatchSize = 8;
    // Token budget specifically for the summary-rewrite call. Larger than maxPredictTokens
    // (which is sized for chat replies) so the rolling memory can actually carry detail.
    // ~300 tokens ≈ 4-8 short sentences ≈ what fits comfortably in 800 chars.
    public static volatile int summaryMaxPredictTokens = 300;

    /**
     * Apply the {@code BOT_LLM_*} keys from the server config (config.yaml {@code server:} block)
     * onto these static toggles at startup. The Anthropic API key is NOT set here — it stays in the
     * ANTHROPIC_API_KEY env var. A blank/typo'd provider leaves the existing default untouched.
     */
    public static void applyServerConfig(config.ServerConfig sc) {
        if (sc == null) {
            return;
        }
        enabled = sc.BOT_LLM_ENABLED;
        banterEnabled = sc.BOT_LLM_BANTER;
        debugLog = sc.BOT_LLM_DEBUG;
        if (sc.BOT_LLM_PROVIDER != null && !sc.BOT_LLM_PROVIDER.isBlank()) {
            try {
                provider = Provider.valueOf(sc.BOT_LLM_PROVIDER.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // keep the default provider when the configured value isn't OLLAMA/ANTHROPIC
            }
        }
        if (sc.BOT_LLM_ANTHROPIC_MODEL != null && !sc.BOT_LLM_ANTHROPIC_MODEL.isBlank()) {
            anthropicModel = sc.BOT_LLM_ANTHROPIC_MODEL.trim();
        }
    }

    private BotLlmConfig() {}
}
