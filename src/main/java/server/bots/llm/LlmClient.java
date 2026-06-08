package server.bots.llm;

import java.util.Optional;

/**
 * Provider switch for bot LLM calls. Routes to the local Ollama backend or the
 * Anthropic (Claude) cloud backend based on {@link BotLlmConfig#provider}. Both
 * return Optional.empty on any failure, so callers stay provider-agnostic.
 */
public final class LlmClient {
    private LlmClient() {}

    public static Optional<String> generate(String prompt, String system) {
        return BotLlmConfig.provider == BotLlmConfig.Provider.ANTHROPIC
                ? AnthropicClient.generate(prompt, system)
                : OllamaClient.generate(prompt, system);
    }

    public static Optional<String> generateLong(String prompt, String system, int maxTokens) {
        return BotLlmConfig.provider == BotLlmConfig.Provider.ANTHROPIC
                ? AnthropicClient.generateLong(prompt, system, maxTokens)
                : OllamaClient.generateLong(prompt, system, maxTokens);
    }
}
