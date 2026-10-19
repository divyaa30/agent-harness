package com.divya.agentharness.tools;

import java.util.Map;

import com.divya.agentharness.llm.ToolSpec;

/**
 * Something the agent can ask us to do, like searching logs.
 * Every tool describes itself (so the model knows when to use it) and can run.
 */
public interface AgentTool {

    /** This tool's entry on the menu we show the model. */
    ToolSpec spec();

    /**
     * Runs the tool with the inputs the model chose.
     * Returns plain text, because the model reads text.
     */
    String execute(Map<String, Object> args);

    /**
     * Same, plus the id of the model's tool call. Tools that change something use it as an
     * idempotency key, so the same request can never be applied twice.
     */
    default String execute(Map<String, Object> args, String toolCallId) {
        return execute(args);
    }

    /** True for tools that change production: the run pauses until a human approves the call. */
    default boolean requiresApproval() {
        return false;
    }
}
