package com.divya.agentharness.agent;

import java.util.List;

/**
 * The outcome of one investigation.
 *
 * @param answer the agent's final report, or why it stopped
 * @param trace  every step in order, for humans to follow what happened
 */
public record AgentResult(boolean completed, String answer, List<String> trace,
                          int llmCalls, int toolCalls, int totalTokens) {
}
