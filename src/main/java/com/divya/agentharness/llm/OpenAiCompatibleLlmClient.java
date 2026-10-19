package com.divya.agentharness.llm;

import java.net.http.HttpClient;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Calls any provider that speaks the OpenAI chat-completions format (Groq, OpenRouter, ...).
 */
@Component
public class OpenAiCompatibleLlmClient implements LlmClient {

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
    public String chat(List<ChatMessage> messages) {
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new IllegalStateException("LLM_API_KEY environment variable is not set");
        }

        ChatResponse response = restClient.post()
                .uri("/chat/completions")
                .body(new ChatRequest(properties.model(), messages))
                .retrieve()
                .body(ChatResponse.class);

        log.info("LLM call used {} prompt + {} completion tokens",
                response.usage().promptTokens(), response.usage().completionTokens());

        return response.choices().get(0).message().content();
    }

    // The JSON we send and receive. Spring converts these records to and from JSON for us.

    private record ChatRequest(String model, List<ChatMessage> messages) {
    }

    private record ChatResponse(List<Choice> choices, Usage usage) {
    }

    private record Choice(ChatMessage message, @JsonProperty("finish_reason") String finishReason) {
    }

    private record Usage(@JsonProperty("prompt_tokens") int promptTokens,
                         @JsonProperty("completion_tokens") int completionTokens) {
    }
}
