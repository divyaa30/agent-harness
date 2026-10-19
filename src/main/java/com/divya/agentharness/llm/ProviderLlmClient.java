package com.divya.agentharness.llm;

/**
 * The client that actually produces model replies: the real provider, or the fake one used
 * for load tests (llm.mode=fake). RetryingLlmClient wraps whichever one is active.
 */
public interface ProviderLlmClient extends LlmClient {
}
