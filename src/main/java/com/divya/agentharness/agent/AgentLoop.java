package com.divya.agentharness.agent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.divya.agentharness.llm.ChatMessage;
import com.divya.agentharness.llm.LlmClient;
import com.divya.agentharness.llm.LlmResponse;
import com.divya.agentharness.llm.ToolCall;
import com.divya.agentharness.tools.ServiceCatalog;
import com.divya.agentharness.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
               Then search ALL of that service's logs (no level filter) from shortly before the errors
               started: the cause often shows up as a WARN or INFO line just before the errors.
            3. Check what changed around that time (get_recent_deploys).
            4. Only blame a deployment if the errors come from the same service and start right after it.
               A deploy that is merely close in time is not proof.
            Separate symptom from cause: "OTPs were delayed" is a symptom. Keep asking why until a
            tool result shows the cause.
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
    private final Duration stepDelay;

    public AgentLoop(LlmClient llmClient, ToolRegistry toolRegistry, ServiceCatalog serviceCatalog,
                     @Value("${agent.step-delay:0s}") Duration stepDelay) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.serviceCatalog = serviceCatalog;
        this.stepDelay = stepDelay;
    }

    /** Told about every new message, and the tokens its model call used (0 for tool results). */
    @FunctionalInterface
    public interface StepListener {
        void onMessage(ChatMessage message, int tokens);
    }

    /**
     * Runs an investigation, or continues one that was interrupted.
     *
     * @param history  messages already saved for this run; empty for a new run
     * @param listener called with every new message the moment it joins the conversation,
     *                 so the caller can save it before the next step starts
     */
    public AgentResult investigate(String incident, List<ChatMessage> history, StepListener listener) {
        // The model has no memory between calls, so this list IS the memory:
        // every call sends the whole conversation so far.
        List<ChatMessage> conversation = new ArrayList<>(history);
        List<String> trace = new ArrayList<>();
        int llmCalls = (int) history.stream().filter(m -> m.role().equals("assistant")).count();
        int toolCalls = (int) history.stream().filter(m -> m.role().equals("tool")).count();
        int tokens = 0;

        if (history.isEmpty()) {
            addMessage(conversation, ChatMessage.system(SYSTEM_PROMPT.formatted(serviceCatalog.describe())), 0, listener);
            addMessage(conversation, ChatMessage.user(incident), 0, listener);
        } else {
            String resumed = "Resumed from " + history.size() + " saved messages (" + llmCalls + " model calls, "
                    + toolCalls + " tool calls already done)";
            log.info(resumed);
            trace.add(resumed);

            // Crashed after the final answer was saved, but before the run was marked finished.
            ChatMessage last = history.get(history.size() - 1);
            if (last.role().equals("assistant") && !last.hasToolCalls()) {
                return new AgentResult(true, last.content(), trace, llmCalls, toolCalls, 0);
            }
            // Crashed after the model asked for tools, but before every result was saved:
            // run just the missing ones. Asking the model again could give a different request.
            toolCalls = runTools(pendingToolCalls(conversation), conversation, trace, listener, toolCalls);
        }

        // The limit counts model calls from before a crash too, so a run that keeps
        // crashing can't spend tokens forever.
        while (llmCalls < MAX_LLM_CALLS) {
            LlmResponse response = llmClient.chat(conversation, toolRegistry.specs());
            llmCalls++;
            int used = response.promptTokens() + response.completionTokens();
            tokens += used;
            addMessage(conversation, response.message(), used, listener);

            // No tool requests means the model has given its final answer.
            if (!response.wantsTools()) {
                trace.add("Final answer after " + llmCalls + " model calls");
                log.info("Investigation finished: {} model calls, {} tool calls", llmCalls, toolCalls);
                return new AgentResult(true, response.message().content(), trace, llmCalls, toolCalls, tokens);
            }

            // Otherwise run every tool it asked for and add each result to the conversation.
            toolCalls = runTools(response.message().toolCalls(), conversation, trace, listener, toolCalls);
        }

        log.warn("Investigation stopped: hit the limit of {} model calls", MAX_LLM_CALLS);
        return new AgentResult(false, "Stopped: reached the limit of " + MAX_LLM_CALLS + " model calls without an answer.",
                trace, llmCalls, toolCalls, tokens);
    }

    /** Runs each tool call, adds its result to the conversation, and returns the new tool-call count. */
    private int runTools(List<ToolCall> calls, List<ChatMessage> conversation, List<String> trace,
                         StepListener listener, int toolCallsSoFar) {
        int toolCalls = toolCallsSoFar;
        for (ToolCall call : calls) {
            toolCalls++;
            String step = "Step " + toolCalls + ": " + call.function().name() + " " + call.function().arguments();
            log.info(step);
            trace.add(step);

            String result = toolRegistry.execute(call);
            addMessage(conversation, ChatMessage.toolResult(call.id(), result), 0, listener);
            pauseBetweenSteps();
        }
        return toolCalls;
    }

    /** Tool calls in the latest model reply that have no saved result yet. */
    private static List<ToolCall> pendingToolCalls(List<ChatMessage> conversation) {
        for (int i = conversation.size() - 1; i >= 0; i--) {
            ChatMessage message = conversation.get(i);
            if (message.role().equals("assistant")) {
                if (!message.hasToolCalls()) {
                    return List.of();
                }
                Set<String> answered = conversation.subList(i + 1, conversation.size()).stream()
                        .filter(m -> m.role().equals("tool"))
                        .map(ChatMessage::toolCallId)
                        .collect(Collectors.toSet());
                return message.toolCalls().stream().filter(call -> !answered.contains(call.id())).toList();
            }
        }
        return List.of();
    }

    /** Every message goes through here: reported to the listener (to be saved), then remembered. */
    private static void addMessage(List<ChatMessage> conversation, ChatMessage message, int tokens,
                                   StepListener listener) {
        listener.onMessage(message, tokens);
        conversation.add(message);
    }

    /** Optional slow-down (agent.step-delay), used to give the crash demo time to kill the app mid-run. */
    private void pauseBetweenSteps() {
        if (stepDelay.isZero()) {
            return;
        }
        try {
            Thread.sleep(stepDelay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
