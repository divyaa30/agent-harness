package com.divya.agentharness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.divya.agentharness.llm.ChatMessage;
import com.divya.agentharness.llm.LlmResponse;
import com.divya.agentharness.llm.OpenAiCompatibleLlmClient;
import com.divya.agentharness.llm.ToolCall;
import com.divya.agentharness.run.AgentRun;
import com.divya.agentharness.run.AgentRunRepository;
import com.divya.agentharness.run.AgentStep;
import com.divya.agentharness.run.AgentStepRepository;
import com.divya.agentharness.run.ApprovalService;
import com.divya.agentharness.run.RunService;
import com.divya.agentharness.run.RunStatus;
import com.divya.agentharness.run.RunSweeper;
import com.divya.agentharness.tools.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * The whole system against a real Postgres and a real Kafka in throwaway containers:
 * real migrations and fake bank, real Kafka worker, real tools. Only the model is scripted.
 */
@SpringBootTest(properties = "agent.sweeper.interval-ms=3600000")  // the test triggers the sweeper itself
@Testcontainers
class AgentHarnessIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @Container
    @ServiceConnection
    static final KafkaContainer kafka = new KafkaContainer("apache/kafka:3.9.1");

    /** The real HTTP client, replaced by a script. The retrying wrapper still sits in front of it. */
    @MockitoBean
    OpenAiCompatibleLlmClient llm;

    @Autowired RunService runService;
    @Autowired ApprovalService approvalService;
    @Autowired RunSweeper sweeper;
    @Autowired ToolRegistry tools;
    @Autowired AgentRunRepository runs;
    @Autowired AgentStepRepository steps;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;

    @Test
    void aQueuedRunGoesThroughKafkaAndUsesTheRealToolsOnTheFakeBank() {
        script(
                toolCall("c1", "search_logs", Map.of("from", "2026-10-15 10:00", "to", "2026-10-15 11:00",
                        "level", "ERROR", "service", "kyc-service")),
                toolCall("c2", "get_recent_deploys", Map.of("from", "2026-10-15 09:00", "to", "2026-10-15 12:00")),
                answer("Root cause: kyc-service 2.3.0 deployed at 10:05"));

        AgentRun run = runService.createRun("Customers could not open accounts on 15 October 2026 in the morning.");
        assertThat(run.getStatus()).isEqualTo(RunStatus.PENDING);  // accepted, not done

        AgentRun done = waitFor(run.getId(), RunStatus.COMPLETED);

        assertThat(done.getResult()).contains("kyc-service 2.3.0");
        assertThat(done.getLlmCalls()).isEqualTo(3);
        assertThat(done.getTotalTokens()).isEqualTo(3 * 150);
        List<String> toolResults = steps.findByRunIdOrderBySeq(run.getId()).stream()
                .filter(s -> s.getRole().equals("tool")).map(AgentStep::getContent).toList();
        // Real data from the fake bank: 139 NullPointerExceptions grouped into one pattern.
        assertThat(toolResults.get(0)).contains("kyc-service x139").contains("PanValidator");
        assertThat(toolResults.get(1)).contains("kyc-service 2.3.0").contains("Rollback to 2.2.4");
    }

    @Test
    void anInterruptedRunResumesFromItsSavedStepsWithoutRepeatingWork() {
        AgentRun run = runs.save(new AgentRun("Morning incident"));
        run.markRunning();
        runs.save(run);
        String pendingCall = json.writeValueAsString(List.of(call("c1", "get_recent_deploys",
                Map.of("from", "2026-10-15 09:00", "to", "2026-10-15 12:00"))));
        steps.save(new AgentStep(run.getId(), 1, "system", "instructions", null, null, 0));
        steps.save(new AgentStep(run.getId(), 2, "user", "Morning incident", null, null, 0));
        steps.save(new AgentStep(run.getId(), 3, "assistant", null, pendingCall, null, 700));
        // ...and then the app "crashed" before the tool result was saved.
        script(answer("Root cause: kyc-service 2.3.0"));

        AgentRun done = runService.process(run.getId());

        assertThat(done.getStatus()).isEqualTo(RunStatus.COMPLETED);
        verify(llm, times(1)).chat(any(), any());  // the old request was not asked again
        List<AgentStep> all = steps.findByRunIdOrderBySeq(run.getId());
        assertThat(all).extracting(AgentStep::getSeq).containsExactly(1, 2, 3, 4, 5);
        assertThat(all.get(3).getToolCallId()).isEqualTo("c1");
        assertThat(done.getTotalTokens()).isEqualTo(700 + 150);  // tokens from before the crash count
    }

    @Test
    void aDuplicateDeliveryOfAFinishedRunIsSkipped() {
        script(answer("Root cause: none"));
        AgentRun run = runs.save(new AgentRun("Anything"));
        runService.process(run.getId());

        runService.process(run.getId());  // Kafka delivered it again

        verify(llm, times(1)).chat(any(), any());
    }

    @Test
    void aRollbackWaitsForApprovalRunsOnceAndCannotBeDecidedTwice() {
        ToolCall rollback = call("r1", "rollback_deployment",
                Map.of("service", "notification-service", "to_version", "1.8.0", "reason", "1.9.0 broke emails"));
        script(new LlmResponse(new ChatMessage("assistant", null, List.of(rollback), null), 100, 50),
                answer("Rolled back notification-service to 1.8.0"));

        AgentRun run = runService.createRun("Welcome emails failing since 19:30 on 15 October 2026, still happening.");
        waitFor(run.getId(), RunStatus.WAITING_APPROVAL);
        assertThat(rollbacksFor("r1")).isZero();  // nothing changed yet

        approvalService.decide(run.getId(), true, "divya", "evidence is clear");
        AgentRun done = waitFor(run.getId(), RunStatus.COMPLETED);

        assertThat(done.getResult()).contains("Rolled back");
        assertThat(rollbacksFor("r1")).isEqualTo(1);
        assertThatThrownBy(() -> approvalService.decide(run.getId(), true, "someone-else", null))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");

        // A replay of the same approved call (as after a crash) changes nothing.
        assertThat(tools.execute(rollback)).startsWith("Already done");
        assertThat(rollbacksFor("r1")).isEqualTo(1);
    }

    @Test
    void aRejectedRollbackNeverRuns() {
        ToolCall rollback = call("r2", "rollback_deployment",
                Map.of("service", "notification-service", "to_version", "1.8.0", "reason", "1.9.0 broke emails"));
        AtomicInteger callNo = new AtomicInteger();
        when(llm.chat(any(), any())).thenAnswer(inv -> {
            if (callNo.incrementAndGet() == 1) {
                return new LlmResponse(new ChatMessage("assistant", null, List.of(rollback), null), 100, 50);
            }
            List<ChatMessage> conversation = inv.getArgument(0);
            String last = conversation.get(conversation.size() - 1).content();
            return answer("Seen: " + last);
        });

        AgentRun run = runService.createRun("Welcome emails failing, still happening.");
        waitFor(run.getId(), RunStatus.WAITING_APPROVAL);
        approvalService.decide(run.getId(), false, "divya", "change freeze tonight");
        AgentRun done = waitFor(run.getId(), RunStatus.COMPLETED);

        assertThat(rollbacksFor("r2")).isZero();
        assertThat(done.getResult()).contains("REJECTED by divya").contains("change freeze tonight");
    }

    @Test
    void theRollbackToolRefusesAVersionTheServiceNeverRan() {
        String result = tools.execute(call("r3", "rollback_deployment",
                Map.of("service", "notification-service", "to_version", "9.9.9", "reason", "test")));

        assertThat(result).startsWith("ERROR").contains("never deployed");
        assertThat(rollbacksFor("r3")).isZero();
    }

    @Test
    void theSweeperRequeuesARunThatNeverReachedKafka() {
        script(answer("Root cause: found"));
        AgentRun orphan = runs.save(new AgentRun("Saved, then the app died before publishing"));
        jdbc.update("UPDATE agent_runs SET updated_at = now() - interval '10 minutes' WHERE id = ?", orphan.getId());

        sweeper.requeueStuckRuns();

        assertThat(waitFor(orphan.getId(), RunStatus.COMPLETED).getResult()).isEqualTo("Root cause: found");
    }

    @Test
    void theFakeBankMatchesItsAnswerKey() {
        String stats = tools.execute(call("t1", "get_application_stats",
                Map.of("from", "2026-10-15 06:00", "to", "2026-10-15 12:00")));
        String redHerring = tools.execute(call("t2", "get_recent_deploys",
                Map.of("from", "2026-10-15 13:00", "to", "2026-10-15 15:00")));
        String kafkaCause = tools.execute(call("t3", "search_logs",
                Map.of("from", "2026-10-15 15:30", "to", "2026-10-15 16:05", "service", "otp-service", "level", "WARN")));

        assertThat(stats).contains("2026-10-15 10:00  total=120  not_approved=68").contains("KYC_ERROR (FAILED): 67");
        assertThat(redHerring).contains("notification-service 1.8.0").doesNotContain("pan-verification-gateway");
        assertThat(kafkaCause).contains("Kafka broker-N unreachable");
    }

    // ------------------------------------------------------------------ helpers

    private void script(LlmResponse... replies) {
        AtomicInteger next = new AtomicInteger();
        when(llm.chat(any(), any())).thenAnswer(inv -> replies[Math.min(next.getAndIncrement(), replies.length - 1)]);
    }

    private ToolCall call(String id, String name, Map<String, Object> args) {
        return new ToolCall(id, "function", new ToolCall.Function(name, json.writeValueAsString(args)));
    }

    private LlmResponse toolCall(String id, String name, Map<String, Object> args) {
        return new LlmResponse(new ChatMessage("assistant", null, List.of(call(id, name, args)), null), 100, 50);
    }

    private static LlmResponse answer(String text) {
        return new LlmResponse(new ChatMessage("assistant", text, null, null), 100, 50);
    }

    private int rollbacksFor(String toolCallId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM deployments WHERE idempotency_key = ?", Integer.class, toolCallId);
        return n == null ? 0 : n;
    }

    /** Polls the database until the run reaches the status (the worker runs on its own thread). */
    private AgentRun waitFor(UUID runId, RunStatus status) {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        AgentRun run = null;
        while (System.nanoTime() < deadline) {
            run = runs.findById(runId).orElseThrow();
            if (run.getStatus() == status) {
                return run;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        throw new AssertionError("Run " + runId + " did not reach " + status + "; it is " + (run == null ? "missing" : run.getStatus()));
    }
}
