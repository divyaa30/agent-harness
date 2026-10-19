package com.divya.agentharness.llm;

import java.util.List;

/**
 * Talks to a language model. The rest of the app only knows this interface,
 * so the provider (Groq today, anything else tomorrow) can be swapped freely.
 */
public interface LlmClient {

    /** Sends the conversation so far and returns the model's reply text. */
    String chat(List<ChatMessage> messages);
}
