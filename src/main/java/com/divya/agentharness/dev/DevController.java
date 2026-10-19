package com.divya.agentharness.dev;

import java.util.List;

import com.divya.agentharness.agent.AgentLoop;
import com.divya.agentharness.agent.AgentResult;
import com.divya.agentharness.llm.ChatMessage;
import com.divya.agentharness.llm.LlmClient;
import com.divya.agentharness.llm.ToolCall;
import com.divya.agentharness.tools.ToolRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Temporary endpoint for trying the LLM connection from a browser.
 * Removed once the real agent API exists.
 */
@RestController
public class DevController {

    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final AgentLoop agentLoop;

    public DevController(LlmClient llmClient, ToolRegistry toolRegistry, AgentLoop agentLoop) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.agentLoop = agentLoop;
    }

    // Runs a full investigation and returns the steps plus the final answer.
    // Try: http://localhost:8080/dev/investigate?incident=Customers could not open accounts on 15 Oct 2026 in the morning
    @GetMapping("/dev/investigate")
    public String investigate(@RequestParam String incident) {
        AgentResult result = agentLoop.investigate(incident);
        return String.join("\n", result.trace())
                + "\n\n" + result.answer()
                + "\n\n(" + result.llmCalls() + " model calls, " + result.toolCalls() + " tool calls, "
                + result.totalTokens() + " tokens)";
    }

    // Runs one tool directly, without the AI, exactly as if the AI had asked for it.
    // Try: http://localhost:8080/dev/tool?name=get_recent_deploys&args={"from":"2026-10-15 09:00","to":"2026-10-15 12:00"}
    @GetMapping("/dev/tool")
    public String tool(@RequestParam String name, @RequestParam String args) {
        return toolRegistry.execute(new ToolCall("dev", "function", new ToolCall.Function(name, args)));
    }

    // Try: http://localhost:8080/dev/ask?q=What is a NullPointerException?
    @GetMapping("/dev/ask")
    public String ask(@RequestParam String q) {
        return llmClient.chat(List.of(
                ChatMessage.system("You are a senior backend engineer. Answer in 2-3 short sentences."),
                ChatMessage.user(q)), List.of()).message().content();
    }
}
