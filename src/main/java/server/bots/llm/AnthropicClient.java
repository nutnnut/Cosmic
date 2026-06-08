package server.bots.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * Minimal Anthropic (Claude) Messages API client — the cloud alternative to
 * {@link OllamaClient}. Same contract: a single user prompt + optional system
 * prompt in, one short reply out, every failure swallowed to Optional.empty so
 * an API hiccup never crashes a bot tick.
 *
 * Auth is the ANTHROPIC_API_KEY env var (see {@link BotLlmConfig#anthropicApiKey}).
 * The reused per-bot system prompt is marked with cache_control so Claude can
 * serve it from the prompt cache — note Claude only caches prefixes over ~4096
 * tokens, so today's short persona prompts won't actually cache; this is wired
 * for when the system prompt grows.
 */
public final class AnthropicClient {
    private static final Logger log = LoggerFactory.getLogger(AnthropicClient.class);

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(5000))
            .build();

    private AnthropicClient() {}

    public static Optional<String> generate(String prompt, String system) {
        return send(prompt, system, BotLlmConfig.maxPredictTokens, BotLlmConfig.requestTimeoutMs);
    }

    /** Variant for non-chat calls (e.g. memory summarization) with a bigger token
     *  budget and a proportionally longer timeout — mirrors OllamaClient.generateLong. */
    public static Optional<String> generateLong(String prompt, String system, int maxTokens) {
        int timeoutMs = Math.max(BotLlmConfig.requestTimeoutMs, 5000 + maxTokens * 200);
        return send(prompt, system, maxTokens, timeoutMs);
    }

    private static Optional<String> send(String prompt, String system, int maxTokens, int timeoutMs) {
        String key = BotLlmConfig.anthropicApiKey;
        if (key == null || key.isBlank()) {
            log.warn("anthropic: ANTHROPIC_API_KEY not set; cannot call Claude");
            return Optional.empty();
        }
        String body = buildBody(prompt, system, maxTokens);
        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(URI.create(BotLlmConfig.anthropicEndpoint))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", key)
                    .header("anthropic-version", BotLlmConfig.anthropicVersion)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
        } catch (Exception e) {
            log.warn("anthropic: request build failed: {}", e.toString());
            return Optional.empty();
        }
        try {
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("anthropic: HTTP {} body: {}", resp.statusCode(), abbrev(resp.body(), 300));
                return Optional.empty();
            }
            String text = extractFirstText(resp.body());
            if (text == null || text.isBlank()) return Optional.empty();
            return Optional.of(text.trim());
        } catch (java.net.http.HttpTimeoutException te) {
            log.info("anthropic: timeout after {}ms", timeoutMs);
            return Optional.empty();
        } catch (Exception e) {
            log.warn("anthropic: send failed: {}", e.toString());
            return Optional.empty();
        }
    }

    private static String buildBody(String prompt, String system, int maxTokens) {
        StringBuilder sb = new StringBuilder(512);
        sb.append('{');
        sb.append("\"model\":\"").append(OllamaClient.jsonEscape(BotLlmConfig.anthropicModel)).append("\",");
        sb.append("\"max_tokens\":").append(maxTokens).append(',');
        if (system != null && !system.isEmpty()) {
            if (BotLlmConfig.anthropicPromptCaching) {
                // System as a single cacheable text block.
                sb.append("\"system\":[{\"type\":\"text\",\"text\":\"")
                        .append(OllamaClient.jsonEscape(system))
                        .append("\",\"cache_control\":{\"type\":\"ephemeral\"}}],");
            } else {
                sb.append("\"system\":\"").append(OllamaClient.jsonEscape(system)).append("\",");
            }
        }
        // No sampling params (temperature/top_p/top_k): omitting them keeps this
        // body valid across every Claude model, including Opus 4.x which rejects them.
        sb.append("\"messages\":[{\"role\":\"user\",\"content\":\"")
                .append(OllamaClient.jsonEscape(prompt)).append("\"}]");
        sb.append('}');
        return sb.toString();
    }

    /**
     * Pull the first content text block out of a Messages API reply without a JSON
     * dependency. Shape: {..."content":[{"type":"text","text":"HERE"}],...}. We scan
     * for a "text" KEY (followed by ':'), which skips the "type":"text" value.
     */
    static String extractFirstText(String json) {
        if (json == null) return null;
        int from = 0;
        while (true) {
            int key = json.indexOf("\"text\"", from);
            if (key < 0) return null;
            int i = key + "\"text\"".length();
            while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
            if (i >= json.length() || json.charAt(i) != ':') { from = key + 1; continue; }
            i++; // past ':'
            while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
            if (i >= json.length() || json.charAt(i) != '"') { from = key + 1; continue; }
            i++; // past opening quote
            return unescape(json, i);
        }
    }

    /** Decode a JSON string body starting at index i (just past the opening quote). */
    private static String unescape(String json, int i) {
        StringBuilder out = new StringBuilder();
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char n = json.charAt(i + 1);
                switch (n) {
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    case 'r' -> out.append('\r');
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'u' -> {
                        if (i + 5 < json.length()) {
                            try {
                                out.append((char) Integer.parseInt(json.substring(i + 2, i + 6), 16));
                            } catch (NumberFormatException ignored) {}
                            i += 4;
                        }
                    }
                    default -> out.append(n);
                }
                i += 2;
            } else if (c == '"') {
                return out.toString();
            } else {
                out.append(c);
                i++;
            }
        }
        return null;
    }

    private static String abbrev(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }
}
