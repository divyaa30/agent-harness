package com.divya.agentharness.llm;

import java.util.List;

/**
 * Talks to a language model. The rest of the app only knows this interface,
 * so the provider (Groq today, anything else tomorrow) can be swapped freely.
 */
public interface LlmClient {

    /**
     * Sends the conversation so far plus the tools the model may ask for.
     * Pass an empty list when the model should just answer in text.
     */
    LlmResponse chat(List<ChatMessage> messages, List<ToolSpec> tools);
}
