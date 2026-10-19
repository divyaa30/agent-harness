package com.divya.agentharness.llm;

import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Calls any provider that speaks the OpenAI chat-completions format (Groq, OpenRouter, ...).
 */
@Component
@ConditionalOnProperty(name = "llm.mode", havingValue = "real", matchIfMissing = true)
public class OpenAiCompatibleLlmClient implements ProviderLlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);

    private final LlmProperties properties;
    private final RestClient restClient;

    public OpenAiCompatibleLlmClient(LlmProperties properties) {
        this.properties = properties;

        // Give up if the provider doesn't answer in time, instead of hanging forever.
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(HttpClient.newHttpClient());
        requestFactory.setReadTimeout(properties.timeout());

        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .defaultHeader("Authorization", "Bearer " + properties.apiKey())
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public LlmResponse chat(List<ChatMessage> messages, List<ToolSpec> tools) {
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new IllegalStateException("LLM_API_KEY environment variable is not set");
        }

        // The provider expects each tool wrapped as {"type":"function","function":{...}}.
        List<Map<String, Object>> toolMenu = tools.isEmpty() ? null : tools.stream()
                .map(tool -> Map.<String, Object>of(
                        "type", "function",
                        "function", Map.of(
                                "name", tool.name(),
                                "description", tool.description(),
                                "parameters", tool.parameters())))
                .toList();

        ChatResponse response = restClient.post()
                .uri("/chat/completions")
                .body(new ChatRequest(properties.model(), messages, toolMenu, properties.temperature()))
                .retrieve()
                .body(ChatResponse.class);

        Usage usage = response.usage();
        log.info("LLM call used {} prompt + {} completion tokens", usage.promptTokens(), usage.completionTokens());

        return new LlmResponse(response.choices().get(0).message(), usage.promptTokens(), usage.completionTokens());
    }

    // The JSON we send and receive. Spring converts these records to and from JSON for us.

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record ChatRequest(String model, List<ChatMessage> messages, List<Map<String, Object>> tools,
                               double temperature) {
    }

    private record ChatResponse(List<Choice> choices, Usage usage) {
    }

    private record Choice(ChatMessage message, @JsonProperty("finish_reason") String finishReason) {
    }

    private record Usage(@JsonProperty("prompt_tokens") int promptTokens,
                         @JsonProperty("completion_tokens") int completionTokens) {
    }
}
