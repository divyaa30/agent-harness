package com.divya.agentharness.dev;

import java.util.List;

import com.divya.agentharness.llm.ChatMessage;
import com.divya.agentharness.llm.LlmClient;
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

    public DevController(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    // Try: http://localhost:8080/dev/ask?q=What is a NullPointerException?
    @GetMapping("/dev/ask")
    public String ask(@RequestParam String q) {
        return llmClient.chat(List.of(
                ChatMessage.system("You are a senior backend engineer. Answer in 2-3 short sentences."),
                ChatMessage.user(q)));
    }
}
