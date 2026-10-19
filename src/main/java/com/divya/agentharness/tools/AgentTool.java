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
}
