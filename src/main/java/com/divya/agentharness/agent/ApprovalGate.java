package com.divya.agentharness.agent;

import java.util.Optional;
import java.util.UUID;

import com.divya.agentharness.llm.ToolCall;

/**
 * Where human decisions about risky tool calls live. The agent loop only asks
 * "has a human decided on this call?" and "please ask a human about it".
 */
public interface ApprovalGate {

    record Decision(boolean approved, String decidedBy, String reason) {
    }

    /** The human's decision on this tool call, if they have made one. */
    Optional<Decision> decisionFor(String toolCallId);

    /** Records that this tool call is waiting for a human. Safe to call twice for the same call. */
    void requestApproval(UUID runId, ToolCall call);
}
