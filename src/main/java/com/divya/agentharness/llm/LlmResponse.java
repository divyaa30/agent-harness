package com.divya.agentharness.llm;

/**
 * What came back from one model call.
 *
 * @param message the model's reply: final text, or tool requests
 */
public record LlmResponse(ChatMessage message, int promptTokens, int completionTokens) {

    /** True when the model wants us to run tools; false when it gave its final answer. */
    public boolean wantsTools() {
        return message.hasToolCalls();
    }
}
