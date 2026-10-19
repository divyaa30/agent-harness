package com.divya.agentharness.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.divya.agentharness.llm.ChatMessage;
import com.divya.agentharness.llm.LlmClient;
import com.divya.agentharness.llm.LlmResponse;
import com.divya.agentharness.llm.ToolCall;
import com.divya.agentharness.tools.ServiceCatalog;
import com.divya.agentharness.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The doctor-and-nurse loop: ask the model, run the tools it picks, send back the
 * results, and repeat until it gives a final answer or we hit the step limit.
 */
@Component
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    /** Safety limit: a confused model must not loop (and spend tokens) forever. */
    static final int MAX_LLM_CALLS = 10;

    static final String SYSTEM_PROMPT = """
            You are an experienced production support engineer at a digital bank.
            You investigate incidents in the account-opening journey using the tools provided.
            All times are UTC.

            The bank's services (use these exact names):
            %s

            Stay inside the date and time of day the reporter gives you.
            Morning means 06:00-12:00, afternoon 12:00-17:00, evening 17:00-22:00.
            Several incidents may have happened that day: report the one that matches the report,
            not simply the biggest one.

            How to investigate:
            1. Confirm the impact and when it started (get_application_stats).
            2. Find the errors and which service they come from (search_logs).
            3. Check what changed around that time (get_recent_deploys).
            4. Only blame a deployment if the errors come from the same service and start right after it.
               A deploy that is merely close in time is not proof.
            Base every claim on tool results. Do not guess.

            When you are confident, reply WITHOUT calling tools, in this format:
            Root cause: <one or two sentences>
            Evidence: <short bullet list with times and numbers from the tools>
            Impact: <who was affected, how many, from when to when>
            Suggested fix: <one or two sentences>
            """;

    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final ServiceCatalog serviceCatalog;

    public AgentLoop(LlmClient llmClient, ToolRegistry toolRegistry, ServiceCatalog serviceCatalog) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.serviceCatalog = serviceCatalog;
    }

    /**
     * @param onMessage called with every message the moment it joins the conversation,
     *                  so the caller can save it before the next step starts
     */
    public AgentResult investigate(String incident, Consumer<ChatMessage> onMessage) {
        // The model has no memory between calls, so this list IS the memory:
        // every call sends the whole conversation so far.
        List<ChatMessage> conversation = new ArrayList<>();
        addMessage(conversation, ChatMessage.system(SYSTEM_PROMPT.formatted(serviceCatalog.describe())), onMessage);
        addMessage(conversation, ChatMessage.user(incident), onMessage);

        List<String> trace = new ArrayList<>();
        int toolCalls = 0;
        int tokens = 0;

        for (int llmCall = 1; llmCall <= MAX_LLM_CALLS; llmCall++) {
            LlmResponse response = llmClient.chat(conversation, toolRegistry.specs());
            tokens += response.promptTokens() + response.completionTokens();
            addMessage(conversation, response.message(), onMessage);

            // No tool requests means the model has given its final answer.
            if (!response.wantsTools()) {
                trace.add("Final answer after " + llmCall + " model calls");
                log.info("Investigation finished: {} model calls, {} tool calls, {} tokens", llmCall, toolCalls, tokens);
                return new AgentResult(true, response.message().content(), trace, llmCall, toolCalls, tokens);
            }

            // Otherwise run every tool it asked for and add each result to the conversation.
            for (ToolCall call : response.message().toolCalls()) {
                toolCalls++;
                String step = "Step " + toolCalls + ": " + call.function().name() + " " + call.function().arguments();
                log.info(step);
                trace.add(step);

                String result = toolRegistry.execute(call);
                addMessage(conversation, ChatMessage.toolResult(call.id(), result), onMessage);
            }
        }

        log.warn("Investigation stopped: hit the limit of {} model calls", MAX_LLM_CALLS);
        return new AgentResult(false, "Stopped: reached the limit of " + MAX_LLM_CALLS + " model calls without an answer.",
                trace, MAX_LLM_CALLS, toolCalls, tokens);
    }

    /** Every message goes through here: reported to the listener (to be saved), then remembered. */
    private static void addMessage(List<ChatMessage> conversation, ChatMessage message, Consumer<ChatMessage> onMessage) {
        onMessage.accept(message);
        conversation.add(message);
    }
}
