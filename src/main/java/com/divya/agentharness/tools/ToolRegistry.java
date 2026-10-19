package com.divya.agentharness.tools;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.divya.agentharness.llm.ToolCall;
import com.divya.agentharness.llm.ToolSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * All the tools the agent may use. Spring finds every AgentTool class automatically.
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, AgentTool> toolsByName;
    private final JsonMapper jsonMapper;

    public ToolRegistry(List<AgentTool> tools, JsonMapper jsonMapper) {
        this.toolsByName = tools.stream().collect(Collectors.toMap(t -> t.spec().name(), Function.identity()));
        this.jsonMapper = jsonMapper;
    }

    /** The menu we show the model. */
    public List<ToolSpec> specs() {
        return toolsByName.values().stream().map(AgentTool::spec).toList();
    }

    /** True if this call is for a tool that changes production and needs a human's approval. */
    public boolean requiresApproval(ToolCall call) {
        AgentTool tool = toolsByName.get(call.function().name());
        return tool != null && tool.requiresApproval();
    }

    /**
     * Runs the tool the model asked for. Never throws: problems go back to the model
     * as text ("ERROR: ..."), so it can correct itself, like a typo'd time.
     */
    public String execute(ToolCall call) {
        String name = call.function().name();
        AgentTool tool = toolsByName.get(name);
        if (tool == null) {
            return "ERROR: unknown tool '" + name + "'. Available: " + toolsByName.keySet();
        }
        try {
            Map<String, Object> args = jsonMapper.readValue(call.function().arguments(), new TypeReference<>() {});
            return tool.execute(args, call.id());
        } catch (Exception e) {
            log.warn("Tool {} failed: {}", name, e.getMessage());
            return "ERROR running " + name + ": " + e.getMessage();
        }
    }
}
