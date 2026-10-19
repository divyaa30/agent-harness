package com.divya.agentharness.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import com.divya.agentharness.agent.AgentResult.Outcome;

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
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The doctor-and-nurse loop: ask the model, run the tools it picks, send back the
 * results, and repeat until it gives a final answer or we hit the step limit.
 */
@Component
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    /** Safety limit: a confused model must not loop (and spend tokens) forever. */
    static final int MAX_LLM_CALLS = 10;

    /** Exact repeats of an earlier tool call that get a nudge; one more and the run stops. */
    static final int MAX_REPEATS = 2;

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

            If a deployment is clearly the cause and the problem is still ongoing (nothing has fixed
            or rolled it back since), CALL rollback_deployment to the previous version instead of only
            suggesting it in your answer. It is safe to call: nothing happens until a human reviews and
            approves it. If they reject it, do not ask again: give your final answer and say what you
            recommend instead. Never propose a rollback for a problem that has already been fixed.

            When you are confident, reply WITHOUT calling tools, in this format:
            Root cause: <one or two sentences>
            Evidence: <short bullet list with times and numbers from the tools>
            Impact: <who was affected, how many, from when to when>
            Suggested fix: <one or two sentences>
            """;

    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final ServiceCatalog serviceCatalog;
    private final ApprovalGate approvalGate;
    private final JsonMapper jsonMapper;
    private final int maxTokens;
    private final Duration maxDuration;
    private final Duration stepDelay;

    public AgentLoop(LlmClient llmClient, ToolRegistry toolRegistry, ServiceCatalog serviceCatalog,
                     ApprovalGate approvalGate, JsonMapper jsonMapper,
                     @Value("${agent.max-tokens:30000}") int maxTokens,
                     @Value("${agent.max-duration:3m}") Duration maxDuration,
                     @Value("${agent.step-delay:0s}") Duration stepDelay) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.serviceCatalog = serviceCatalog;
        this.approvalGate = approvalGate;
        this.jsonMapper = jsonMapper;
        this.maxTokens = maxTokens;
        this.maxDuration = maxDuration;
        this.stepDelay = stepDelay;
    }

    /** Told about every new message, and the tokens its model call used (0 for tool results). */
    @FunctionalInterface
    public interface StepListener {
        void onMessage(ChatMessage message, int tokens);
    }

    /** Everything one investigation keeps track of while it runs. */
    private static final class Run {
        final UUID runId;
        final List<ChatMessage> conversation;
        final List<String> trace = new ArrayList<>();
        final Set<String> toolCallsSeen = new HashSet<>();
        final StepListener listener;
        final int tokensBefore;
        int llmCalls;
        int toolCalls;
        int tokens;
        int repeats;
        String waitingFor;  // set when a risky tool call is waiting for a human

        Run(UUID runId, List<ChatMessage> history, int tokensBefore, StepListener listener) {
            this.runId = runId;
            // The model has no memory between calls, so this list IS the memory:
            // every call sends the whole conversation so far.
            this.conversation = new ArrayList<>(history);
            this.tokensBefore = tokensBefore;
            this.listener = listener;
            this.llmCalls = (int) history.stream().filter(m -> m.role().equals("assistant")).count();
            this.toolCalls = (int) history.stream().filter(m -> m.role().equals("tool")).count();
        }

        AgentResult stopped(String reason) {
            log.warn("Investigation stopped: {}", reason);
            trace.add("Stopped: " + reason);
            return new AgentResult(Outcome.STOPPED, "Stopped: " + reason, trace, llmCalls, toolCalls, tokens);
        }

        AgentResult waiting() {
            return new AgentResult(Outcome.WAITING_FOR_APPROVAL, "Waiting for human approval: " + waitingFor,
                    trace, llmCalls, toolCalls, tokens);
        }
    }

    /**
     * Runs an investigation, or continues one that was interrupted.
     *
     * @param history      messages already saved for this run; empty for a new run
     * @param tokensBefore tokens already spent on this run before a crash (0 for a new run)
     * @param listener     called with every new message the moment it joins the conversation,
     *                     so the caller can save it before the next step starts
     */
    public AgentResult investigate(UUID runId, String incident, List<ChatMessage> history, int tokensBefore,
                                   StepListener listener) {
        Run run = new Run(runId, history, tokensBefore, listener);
        Instant deadline = Instant.now().plus(maxDuration);

        if (history.isEmpty()) {
            addMessage(run, ChatMessage.system(SYSTEM_PROMPT.formatted(serviceCatalog.describe())), 0);
            addMessage(run, ChatMessage.user(incident), 0);
        } else {
            String resumed = "Resumed from " + history.size() + " saved messages (" + run.llmCalls + " model calls, "
                    + run.toolCalls + " tool calls already done)";
            log.info(resumed);
            run.trace.add(resumed);

            // Crashed after the final answer was saved, but before the run was marked finished.
            ChatMessage last = history.get(history.size() - 1);
            if (last.role().equals("assistant") && !last.hasToolCalls()) {
                return new AgentResult(Outcome.ANSWERED, last.content(), run.trace, run.llmCalls, run.toolCalls, 0);
            }
            // Remember earlier calls so repeats are still caught after a resume...
            List<ToolCall> pending = pendingToolCalls(run.conversation);
            history.stream().filter(ChatMessage::hasToolCalls).flatMap(m -> m.toolCalls().stream())
                    .filter(call -> !pending.contains(call))
                    .forEach(call -> run.toolCallsSeen.add(fingerprint(call)));
            // ...then run the tool requests that never got a saved result (after a crash, or a
            // human decision). Asking the model again could give a different request.
            runTools(run, pending);
            if (run.waitingFor != null) {
                return run.waiting();
            }
        }

        while (true) {
            String overBudget = budgetExceeded(run, deadline);
            if (overBudget != null) {
                return run.stopped(overBudget);
            }

            LlmResponse response = llmClient.chat(run.conversation, toolRegistry.specs());
            int used = response.promptTokens() + response.completionTokens();
            run.llmCalls++;
            run.tokens += used;
            addMessage(run, response.message(), used);

            // No tool requests means the model has given its final answer.
            if (!response.wantsTools()) {
                run.trace.add("Final answer after " + run.llmCalls + " model calls");
                log.info("Investigation finished: {} model calls, {} tool calls", run.llmCalls, run.toolCalls);
                return new AgentResult(Outcome.ANSWERED, response.message().content(), run.trace, run.llmCalls,
                        run.toolCalls, run.tokens);
            }

            // Otherwise run every tool it asked for and add each result to the conversation.
            runTools(run, response.message().toolCalls());
            if (run.waitingFor != null) {
                return run.waiting();
            }
            if (run.repeats > MAX_REPEATS) {
                return run.stopped("stuck in a loop, kept repeating tool calls it had already made");
            }
        }
    }

    /** Null while the run is within budget; otherwise why it must stop. */
    private String budgetExceeded(Run run, Instant deadline) {
        // Model calls and tokens from before a crash count too, so a run that keeps
        // crashing can't spend forever.
        if (run.llmCalls >= MAX_LLM_CALLS) {
            return "reached the limit of " + MAX_LLM_CALLS + " model calls without an answer";
        }
        int totalTokens = run.tokensBefore + run.tokens;
        if (totalTokens >= maxTokens) {
            return "used " + totalTokens + " tokens, over the budget of " + maxTokens;
        }
        if (Instant.now().isAfter(deadline)) {
            return "took longer than the time budget of " + maxDuration.toSeconds() + "s";
        }
        return null;
    }

    /**
     * Runs each tool call (unless it's an exact repeat) and adds its result to the conversation.
     * Stops early if a risky call needs a human: that call stays without a result, so it is
     * "pending" and gets picked up when the run resumes after the decision.
     */
    private void runTools(Run run, List<ToolCall> calls) {
        for (ToolCall call : calls) {
            String result;
            if (!run.toolCallsSeen.add(fingerprint(call))) {
                // Same tool, same inputs: the result can't have changed. Don't run it; nudge the model.
                run.repeats++;
                String blocked = "Repeat blocked: " + call.function().name() + " " + call.function().arguments();
                log.info(blocked);
                run.trace.add(blocked);
                result = "Not run again: you already called " + call.function().name()
                        + " with exactly these inputs, and its result is above. "
                        + "Use a different query, or give your final answer.";
            } else if (toolRegistry.requiresApproval(call)) {
                Optional<ApprovalGate.Decision> decision = approvalGate.decisionFor(call.id());
                if (decision.isEmpty()) {
                    approvalGate.requestApproval(run.runId, call);
                    run.waitingFor = call.function().name() + " " + call.function().arguments();
                    String waiting = "Waiting for human approval: " + run.waitingFor;
                    log.info(waiting);
                    run.trace.add(waiting);
                    return;
                }
                ApprovalGate.Decision d = decision.get();
                if (d.approved()) {
                    run.toolCalls++;
                    String step = "Step " + run.toolCalls + " (approved by " + d.decidedBy() + "): "
                            + call.function().name() + " " + call.function().arguments();
                    log.info(step);
                    run.trace.add(step);
                    result = toolRegistry.execute(call);
                } else {
                    String rejected = "Rejected by " + d.decidedBy() + ": " + call.function().name();
                    log.info(rejected);
                    run.trace.add(rejected);
                    result = "REJECTED by " + d.decidedBy() + ": " + d.reason() + ". Do not try this action again. "
                            + "Give your final answer, and recommend what a human should do instead.";
                }
            } else {
                run.toolCalls++;
                String step = "Step " + run.toolCalls + ": " + call.function().name() + " " + call.function().arguments();
                log.info(step);
                run.trace.add(step);
                result = toolRegistry.execute(call);
            }
            addMessage(run, ChatMessage.toolResult(call.id(), result), 0);
            pauseBetweenSteps();
        }
    }

    /**
     * Tool name + inputs with the keys sorted, so {"from":1,"to":2} and {"to":2,"from":1}
     * count as the same call.
     */
    private String fingerprint(ToolCall call) {
        String args = call.function().arguments();
        try {
            Map<String, Object> parsed = jsonMapper.readValue(args, new TypeReference<Map<String, Object>>() {});
            args = new TreeMap<>(parsed).toString();
        } catch (RuntimeException e) {
            // Not valid JSON: compare the raw text instead.
        }
        return call.function().name() + " " + args;
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
    private static void addMessage(Run run, ChatMessage message, int tokens) {
        run.listener.onMessage(message, tokens);
        run.conversation.add(message);
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
