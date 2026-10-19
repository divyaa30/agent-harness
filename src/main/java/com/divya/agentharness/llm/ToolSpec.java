package com.divya.agentharness.llm;

import java.util.Map;

/**
 * One entry on the "menu" of tools we show the model.
 *
 * @param name        what the model calls it, e.g. "search_logs"
 * @param description when to use it; the model reads this to decide
 * @param parameters  its inputs, described as a JSON Schema
 */
public record ToolSpec(String name, String description, Map<String, Object> parameters) {
}
