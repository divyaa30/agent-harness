package com.divya.agentharness.agent;

import java.util.List;

/**
 * The outcome of one processing attempt of an investigation.
 *
 * @param answer the agent's final report, why it stopped, or what it is waiting for
 * @param trace  every step in order, for humans to follow what happened
 */
public record AgentResult(Outcome outcome, String answer, List<String> trace,
                          int llmCalls, int toolCalls, int totalTokens) {

    public enum Outcome {
        ANSWERED,              // the model gave its final report
        STOPPED,               // a limit was hit (calls, tokens, time, loop)
        WAITING_FOR_APPROVAL   // paused: a risky tool call needs a human decision
    }
}
