package server.bots.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-bot conversation memory. Plain-text, on disk, gitignored.
 * - <name>.jsonl       : append-only turn log, one JSON-ish line per exchange.
 *                        NEVER truncated — full history preserved for data hoarding.
 * - <name>.summary.txt : rolling LLM-compacted summary of turns 0..cursor.
 * - <name>.cursor      : integer; how many leading jsonl turns have been folded
 *                        into summary. Advanced only on successful summarization.
 *
 * Gap-free design: the prompt always shows ALL turns from cursor..end verbatim.
 * Summary covers everything before cursor. Together they span the full history.
 * Compaction fires when (total - cursor) exceeds recentTurnsInPrompt + compactBatchSize;
 * it folds the oldest compactBatchSize uncompacted turns into the summary and advances
 * the cursor by that amount. Failures leave the cursor untouched (try again next turn).
 */
public final class BotMemoryStore {
    private static final Logger log = LoggerFactory.getLogger(BotMemoryStore.class);

    public record Turn(long ts, String relation, String sender, String msg, String reply) {}

    private static final ConcurrentHashMap<String, AtomicBoolean> COMPACTION_LOCKS = new ConcurrentHashMap<>();

    private BotMemoryStore() {}

    public static synchronized void ensureDir() {
        try {
            Path dir = Paths.get(BotLlmConfig.memoryDir);
            Files.createDirectories(dir);
            Path ignore = dir.resolve(".gitignore");
            if (!Files.exists(ignore)) {
                Files.writeString(ignore, "*\n!.gitignore\n", StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.warn("memory: ensureDir failed: {}", e.toString());
        }
    }

    public static String loadSummary(String botName) {
        Path p = summaryPath(botName);
        if (!Files.exists(p)) return "";
        try {
            return Files.readString(p, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    /** Returns ALL uncompacted turns (cursor..end), oldest first. These plus the
     *  summary together cover the bot's full conversation history with no gap. */
    public static List<Turn> loadUncompacted(String botName) {
        Path p = jsonlPath(botName);
        if (!Files.exists(p)) return List.of();
        int cursor = loadCursor(botName);
        try (BufferedReader br = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            List<String> all = new ArrayList<>();
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.isBlank()) all.add(line);
            }
            if (cursor > all.size()) cursor = all.size(); // sanity if cursor file got out of sync
            List<Turn> out = new ArrayList<>(all.size() - cursor);
            for (int i = cursor; i < all.size(); i++) {
                Turn t = parseTurn(all.get(i));
                if (t != null) out.add(t);
            }
            return out;
        } catch (IOException e) {
            return List.of();
        }
    }

    public static void appendTurn(String botName, Turn turn) {
        ensureDir();
        Path p = jsonlPath(botName);
        String line = serializeTurn(turn) + "\n";
        try {
            Files.writeString(p, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("memory: append failed for {}: {}", botName, e.toString());
        }
    }

    public static int countTurns(String botName) {
        Path p = jsonlPath(botName);
        if (!Files.exists(p)) return 0;
        try (BufferedReader br = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            int n = 0;
            while (br.readLine() != null) n++;
            return n;
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * If uncompacted turns exceed recentTurnsInPrompt + compactBatchSize, fold the
     * oldest compactBatchSize uncompacted turns into the rolling summary (previous
     * summary is fed back in so "core memory" survives across compactions) and
     * advance the cursor. The jsonl is never modified — history is preserved.
     * On Ollama failure the cursor is NOT advanced; the next call retries.
     */
    public static void compact(String botName) {
        AtomicBoolean lock = COMPACTION_LOCKS.computeIfAbsent(botName, k -> new AtomicBoolean(false));
        if (!lock.compareAndSet(false, true)) return;
        try {
            Path p = jsonlPath(botName);
            if (!Files.exists(p)) return;
            List<String> all;
            try (BufferedReader br = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                all = new ArrayList<>();
                String line;
                while ((line = br.readLine()) != null) {
                    if (!line.isBlank()) all.add(line);
                }
            }
            int cursor = loadCursor(botName);
            if (cursor > all.size()) cursor = all.size();
            int uncompacted = all.size() - cursor;
            int keep = BotLlmConfig.recentTurnsInPrompt;
            int batch = BotLlmConfig.compactBatchSize;
            if (uncompacted <= keep + batch) return; // not enough to bother

            List<String> oldLines = all.subList(cursor, cursor + batch);
            String previousSummary = loadSummary(botName);
            String newSummary = trySummarize(botName, previousSummary, oldLines);
            if (newSummary == null || newSummary.isBlank()) {
                log.info("memory: compact skipped for {} (summarizer unavailable, will retry)", botName);
                return;
            }
            Files.writeString(summaryPath(botName), newSummary.trim() + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            saveCursor(botName, cursor + batch);
            log.info("memory: compacted {} (cursor {} -> {}, jsonl {} lines preserved)",
                    botName, cursor, cursor + batch, all.size());
        } catch (IOException e) {
            log.warn("memory: compact failed for {}: {}", botName, e.toString());
        } finally {
            lock.set(false);
        }
    }

    /** Number of uncompacted turns (total jsonl lines - cursor). Caller can use this
     *  to decide whether to trigger compact() without re-reading the jsonl twice. */
    public static int countUncompacted(String botName) {
        return Math.max(0, countTurns(botName) - loadCursor(botName));
    }

    static int loadCursor(String botName) {
        Path p = cursorPath(botName);
        if (!Files.exists(p)) return 0;
        try {
            String s = Files.readString(p, StandardCharsets.UTF_8).trim();
            return s.isEmpty() ? 0 : Math.max(0, Integer.parseInt(s));
        } catch (Exception e) {
            return 0;
        }
    }

    static void saveCursor(String botName, int value) throws IOException {
        Files.writeString(cursorPath(botName), Integer.toString(Math.max(0, value)),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static String trySummarize(String botName, String previousSummary, List<String> oldLines) {
        if (!BotLlmConfig.enabled) return null;
        StringBuilder convo = new StringBuilder();
        if (!previousSummary.isBlank()) {
            convo.append("Prior memory (keep core facts from this; merge new chat into it, do not lose anything important):\n")
                    .append(previousSummary).append("\n\n");
        }
        convo.append("New chat to fold in:\n");
        for (String l : oldLines) {
            Turn t = parseTurn(l);
            if (t == null) continue;
            convo.append(t.sender).append(" (").append(t.relation).append("): ").append(t.msg).append("\n");
            convo.append(botName).append(": ").append(t.reply).append("\n");
        }
        String sys = "You maintain a rolling memory note about an MMO character named " + botName + ". "
                + "Output ONLY the updated memory — no preamble, no quotes, no labels. "
                + "Write 4-8 short factual sentences in third person. Persist across rewrites: "
                + "who each speaker is (owner / party / strangers), their stated names, recurring topics, "
                + "promises made, gear/items mentioned, places visited, jokes/nicknames, anything personal. "
                + "Drop small-talk filler. If prior memory exists, integrate the new chat into it without "
                + "discarding earlier facts unless directly contradicted. Stay under 800 chars.";
        return LlmClient.generateLong(convo.toString(), sys, BotLlmConfig.summaryMaxPredictTokens).orElse(null);
    }

    private static Path jsonlPath(String botName) {
        return Paths.get(BotLlmConfig.memoryDir, sanitize(botName) + ".jsonl");
    }

    private static Path summaryPath(String botName) {
        return Paths.get(BotLlmConfig.memoryDir, sanitize(botName) + ".summary.txt");
    }

    private static Path cursorPath(String botName) {
        return Paths.get(BotLlmConfig.memoryDir, sanitize(botName) + ".cursor");
    }

    private static String sanitize(String name) {
        StringBuilder out = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-') out.append(c);
            else out.append('_');
        }
        return out.length() == 0 ? "_" : out.toString();
    }

    static String serializeTurn(Turn t) {
        return "{\"ts\":" + t.ts
                + ",\"rel\":\"" + OllamaClient.jsonEscape(t.relation) + "\""
                + ",\"who\":\"" + OllamaClient.jsonEscape(t.sender) + "\""
                + ",\"msg\":\"" + OllamaClient.jsonEscape(t.msg) + "\""
                + ",\"reply\":\"" + OllamaClient.jsonEscape(t.reply) + "\"}";
    }

    static Turn parseTurn(String line) {
        try {
            long ts = Long.parseLong(extractField(line, "\"ts\":"));
            String rel = extractStringField(line, "\"rel\":\"");
            String who = extractStringField(line, "\"who\":\"");
            String msg = extractStringField(line, "\"msg\":\"");
            String reply = extractStringField(line, "\"reply\":\"");
            return new Turn(ts, rel, who, msg, reply);
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractField(String line, String key) {
        int i = line.indexOf(key);
        if (i < 0) return "0";
        i += key.length();
        int end = i;
        while (end < line.length() && (Character.isDigit(line.charAt(end)) || line.charAt(end) == '-')) end++;
        return line.substring(i, end);
    }

    private static String extractStringField(String line, String key) {
        int i = line.indexOf(key);
        if (i < 0) return "";
        i += key.length();
        StringBuilder out = new StringBuilder();
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == '\\' && i + 1 < line.length()) {
                char n = line.charAt(i + 1);
                switch (n) {
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    case 'r' -> out.append('\r');
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
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
        return out.toString();
    }
}
