package com.divya.agentharness.llm;

/**
 * One message in a conversation with the model.
 * role is "system" (instructions), "user" (our question) or "assistant" (the model's reply).
 */
public record ChatMessage(String role, String content) {

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content);
    }
}
