package com.divya.agentharness.llm;

/**
 * The model asking us to run a tool, e.g. search_logs with {"service":"kyc-service"}.
 * The shape matches the provider's JSON exactly, so it converts automatically.
 *
 * @param id       reference number; our result must quote it back
 * @param function which tool, and its inputs as a JSON string
 */
public record ToolCall(String id, String type, Function function) {

    public record Function(String name, String arguments) {
    }
}
