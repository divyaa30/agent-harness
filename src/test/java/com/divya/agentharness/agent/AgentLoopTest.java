package com.divya.agentharness.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import com.divya.agentharness.agent.AgentResult.Outcome;
import com.divya.agentharness.llm.ChatMessage;
import com.divya.agentharness.llm.LlmClient;
import com.divya.agentharness.llm.LlmResponse;
import com.divya.agentharness.llm.ToolCall;
import com.divya.agentharness.llm.ToolSpec;
import com.divya.agentharness.tools.AgentTool;
import com.divya.agentharness.tools.ServiceCatalog;
import com.divya.agentharness.tools.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The agent loop with a scripted fake model: no network, no database, no tokens.
 * Every limit and every resume path can be tested on demand.
 */
class AgentLoopTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final UUID runId = UUID.randomUUID();

    private FakeLlm llm;
    private CountingTool logs;
    private CountingTool rollback;
    private FakeGate gate;
    private final List<ChatMessage> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        llm = new FakeLlm();
        logs = new CountingTool("search_logs", false);
        rollback = new CountingTool("rollback_deployment", true);
        gate = new FakeGate();
    }

    private AgentLoop loop(int maxTokens, Duration maxDuration) {
        ServiceCatalog catalog = mock(ServiceCatalog.class);
        when(catalog.describe()).thenReturn("- kyc-service: verifies documents");
        ToolRegistry registry = new ToolRegistry(List.of(logs, rollback), json);
        return new AgentLoop(llm, registry, catalog, gate, json, maxTokens, maxDuration, Duration.ZERO);
    }

    private AgentLoop loop() {
        return loop(1_000_000, Duration.ofMinutes(1));
    }

    private AgentResult investigate(AgentLoop loop, List<ChatMessage> history) {
        return loop.investigate(runId, "Accounts failing in the morning", history, 0, (m, t) -> saved.add(m));
    }

    // ---------------------------------------------------------------- happy paths

    @Test
    void answersStraightAwayWhenNoToolIsNeeded() {
        llm.reply(answer("Root cause: none"));

        AgentResult result = investigate(loop(), List.of());

        assertThat(result.outcome()).isEqualTo(Outcome.ANSWERED);
        assertThat(result.answer()).isEqualTo("Root cause: none");
        assertThat(result.llmCalls()).isEqualTo(1);
        assertThat(saved).extracting(ChatMessage::role).containsExactly("system", "user", "assistant");
    }

    @Test
    void runsTheRequestedToolAndSendsTheResultBack() {
        llm.reply(toolCall("c1", "search_logs", "{\"service\":\"kyc-service\"}"));
        llm.reply(answer("Root cause: bad deploy"));

        AgentResult result = investigate(loop(), List.of());

        assertThat(result.outcome()).isEqualTo(Outcome.ANSWERED);
        assertThat(logs.executions).isEqualTo(1);
        assertThat(saved).extracting(ChatMessage::role)
                .containsExactly("system", "user", "assistant", "tool", "assistant");
        assertThat(saved.get(3).toolCallId()).isEqualTo("c1");
        // The second model call saw the tool result: the conversation is resent every time.
        assertThat(llm.conversationsSeen.get(1)).hasSize(4);
    }

    // ---------------------------------------------------------------- limits

    @Test
    void stopsAtTheModelCallLimit() {
        for (int i = 0; i < 20; i++) {
            llm.reply(toolCall("c" + i, "search_logs", "{\"page\":" + i + "}"));  // always a new call
        }

        AgentResult result = investigate(loop(), List.of());

        assertThat(result.outcome()).isEqualTo(Outcome.STOPPED);
        assertThat(result.answer()).contains("limit of 10 model calls");
        assertThat(llm.calls).isEqualTo(10);
    }

    @Test
    void stopsWhenTheTokenBudgetRunsOut() {
        for (int i = 0; i < 5; i++) {
            llm.reply(new LlmResponse(assistantCalling(call("c" + i, "search_logs", "{\"page\":" + i + "}")), 500, 100));
        }

        AgentResult result = investigate(loop(1000, Duration.ofMinutes(1)), List.of());

        assertThat(result.outcome()).isEqualTo(Outcome.STOPPED);
        assertThat(result.answer()).contains("over the budget of 1000");
        assertThat(llm.calls).isEqualTo(2);  // 600 + 600 = 1200: checked before the third call
    }

    @Test
    void tokensSpentBeforeACrashCountTowardsTheBudget() {
        llm.reply(new LlmResponse(assistantCalling(call("c1", "search_logs", "{}")), 500, 100));

        AgentResult result = loop(1000, Duration.ofMinutes(1))
                .investigate(runId, "incident", List.of(), 900, (m, t) -> saved.add(m));

        assertThat(result.outcome()).isEqualTo(Outcome.STOPPED);
        assertThat(llm.calls).isEqualTo(1);
    }

    @Test
    void stopsWhenTheTimeBudgetRunsOut() {
        for (int i = 0; i < 20; i++) {
            llm.reply(() -> {
                sleep(40);
                return toolCall("c" + llm.calls, "search_logs", "{\"page\":" + llm.calls + "}");
            });
        }

        AgentResult result = investigate(loop(1_000_000, Duration.ofMillis(100)), List.of());

        assertThat(result.outcome()).isEqualTo(Outcome.STOPPED);
        assertThat(result.answer()).contains("time budget");
        assertThat(llm.calls).isLessThan(10);
    }

    @Test
    void blocksRepeatedToolCallsAndStopsALoop() {
        llm.reply(toolCall("c1", "search_logs", "{\"from\":\"10:00\",\"to\":\"11:00\"}"));
        llm.reply(toolCall("c2", "search_logs", "{\"to\":\"11:00\",\"from\":\"10:00\"}"));  // same call, keys swapped
        llm.reply(toolCall("c3", "search_logs", "{\"from\":\"10:00\",\"to\":\"11:00\"}"));
        llm.reply(toolCall("c4", "search_logs", "{\"from\":\"10:00\",\"to\":\"11:00\"}"));
        llm.reply(answer("never reached"));

        AgentResult result = investigate(loop(), List.of());

        assertThat(logs.executions).isEqualTo(1);  // only the first one really ran
        assertThat(result.outcome()).isEqualTo(Outcome.STOPPED);
        assertThat(result.answer()).contains("stuck in a loop");
        assertThat(saved).filteredOn(m -> m.role().equals("tool")).extracting(ChatMessage::content)
                .anyMatch(text -> text.startsWith("Not run again"));
    }

    @Test
    void aRepeatGetsANudgeAndTheModelCanRecover() {
        llm.reply(toolCall("c1", "search_logs", "{\"from\":\"10:00\"}"));
        llm.reply(toolCall("c2", "search_logs", "{\"from\":\"10:00\"}"));  // one repeat
        llm.reply(answer("Root cause: found it"));

        AgentResult result = investigate(loop(), List.of());

        assertThat(result.outcome()).isEqualTo(Outcome.ANSWERED);
        assertThat(logs.executions).isEqualTo(1);
    }

    // ---------------------------------------------------------------- resume after a crash

    @Test
    void resumeRunsToolCallsThatHaveNoSavedResultWithoutAskingTheModelAgain() {
        List<ChatMessage> history = List.of(
                ChatMessage.system("instructions"),
                ChatMessage.user("incident"),
                assistantCalling(call("c1", "search_logs", "{\"service\":\"kyc-service\"}")));  // crashed here
        llm.reply(answer("Root cause: bad deploy"));

        AgentResult result = investigate(loop(), history);

        assertThat(logs.executions).isEqualTo(1);
        assertThat(llm.calls).isEqualTo(1);  // only the final answer; the old request was not re-asked
        assertThat(result.outcome()).isEqualTo(Outcome.ANSWERED);
        assertThat(result.llmCalls()).isEqualTo(2);  // counts the call made before the crash
        assertThat(saved.get(0).toolCallId()).isEqualTo("c1");
    }

    @Test
    void resumeFinishesAtOnceIfTheFinalAnswerWasAlreadySaved() {
        List<ChatMessage> history = List.of(
                ChatMessage.system("instructions"),
                ChatMessage.user("incident"),
                new ChatMessage("assistant", "Root cause: saved before the crash", null, null));

        AgentResult result = investigate(loop(), history);

        assertThat(result.outcome()).isEqualTo(Outcome.ANSWERED);
        assertThat(result.answer()).isEqualTo("Root cause: saved before the crash");
        assertThat(llm.calls).isZero();
    }

    @Test
    void resumeStillCatchesRepeatsOfCallsMadeBeforeTheCrash() {
        List<ChatMessage> history = List.of(
                ChatMessage.system("instructions"),
                ChatMessage.user("incident"),
                assistantCalling(call("c1", "search_logs", "{\"from\":\"10:00\"}")),
                ChatMessage.toolResult("c1", "logs"));
        llm.reply(toolCall("c2", "search_logs", "{\"from\":\"10:00\"}"));
        llm.reply(answer("done"));

        investigate(loop(), history);

        assertThat(logs.executions).isZero();
    }

    // ---------------------------------------------------------------- approval gate

    @Test
    void aRiskyToolPausesTheRunInsteadOfRunning() {
        llm.reply(toolCall("r1", "rollback_deployment", "{\"service\":\"notification-service\"}"));

        AgentResult result = investigate(loop(), List.of());

        assertThat(result.outcome()).isEqualTo(Outcome.WAITING_FOR_APPROVAL);
        assertThat(rollback.executions).isZero();
        assertThat(gate.requested).containsExactly("r1");
        // No result is saved for it, so on resume it is still "pending".
        assertThat(saved).noneMatch(m -> "r1".equals(m.toolCallId()));
    }

    @Test
    void anApprovedRiskyToolRunsWhenTheRunResumes() {
        gate.decisions.put("r1", new ApprovalGate.Decision(true, "divya", "clear evidence"));
        List<ChatMessage> history = List.of(
                ChatMessage.system("instructions"),
                ChatMessage.user("incident"),
                assistantCalling(call("r1", "rollback_deployment", "{\"service\":\"notification-service\"}")));
        llm.reply(answer("Rolled back; emails recovering"));

        AgentResult result = investigate(loop(), history);

        assertThat(rollback.executions).isEqualTo(1);
        assertThat(result.outcome()).isEqualTo(Outcome.ANSWERED);
    }

    @Test
    void aRejectedRiskyToolNeverRunsAndTheModelIsToldWhy() {
        gate.decisions.put("r1", new ApprovalGate.Decision(false, "divya", "change freeze tonight"));
        List<ChatMessage> history = List.of(
                ChatMessage.system("instructions"),
                ChatMessage.user("incident"),
                assistantCalling(call("r1", "rollback_deployment", "{\"service\":\"notification-service\"}")));
        llm.reply(answer("Recommend a hotfix instead"));

        AgentResult result = investigate(loop(), history);

        assertThat(rollback.executions).isZero();
        assertThat(saved.get(0).content()).contains("REJECTED by divya").contains("change freeze tonight");
        assertThat(result.outcome()).isEqualTo(Outcome.ANSWERED);
    }

    // ---------------------------------------------------------------- fakes and helpers

    private static ToolCall call(String id, String name, String args) {
        return new ToolCall(id, "function", new ToolCall.Function(name, args));
    }

    private static ChatMessage assistantCalling(ToolCall... calls) {
        return new ChatMessage("assistant", null, List.of(calls), null);
    }

    private static LlmResponse toolCall(String id, String name, String args) {
        return new LlmResponse(assistantCalling(call(id, name, args)), 100, 20);
    }

    private static LlmResponse answer(String text) {
        return new LlmResponse(new ChatMessage("assistant", text, null, null), 100, 50);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Returns scripted replies in order, and remembers what it was sent. */
    static final class FakeLlm implements LlmClient {
        final Deque<Supplier<LlmResponse>> script = new ArrayDeque<>();
        final List<List<ChatMessage>> conversationsSeen = new ArrayList<>();
        int calls;

        void reply(LlmResponse response) {
            script.add(() -> response);
        }

        void reply(Supplier<LlmResponse> response) {
            script.add(response);
        }

        @Override
        public LlmResponse chat(List<ChatMessage> messages, List<ToolSpec> tools) {
            calls++;
            conversationsSeen.add(List.copyOf(messages));
            if (script.isEmpty()) {
                throw new IllegalStateException("The fake model ran out of scripted replies");
            }
            return script.poll().get();
        }
    }

    /** A tool that only counts how often it really ran. */
    static final class CountingTool implements AgentTool {
        final String name;
        final boolean risky;
        int executions;

        CountingTool(String name, boolean risky) {
            this.name = name;
            this.risky = risky;
        }

        @Override
        public ToolSpec spec() {
            return new ToolSpec(name, "test tool", Map.of("type", "object"));
        }

        @Override
        public String execute(Map<String, Object> args) {
            executions++;
            return name + " result";
        }

        @Override
        public boolean requiresApproval() {
            return risky;
        }
    }

    /** Human decisions, held in memory. */
    static final class FakeGate implements ApprovalGate {
        final Map<String, Decision> decisions = new HashMap<>();
        final List<String> requested = new ArrayList<>();

        @Override
        public Optional<Decision> decisionFor(String toolCallId) {
            return Optional.ofNullable(decisions.get(toolCallId));
        }

        @Override
        public void requestApproval(UUID runId, ToolCall call) {
            requested.add(call.id());
        }
    }
}
