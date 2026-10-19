package com.divya.agentharness.llm;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * A stand-in model for load and chaos tests (llm.mode=fake). It always investigates the same
 * way: three real tool calls against the fake bank, then a final answer, each reply after a
 * simulated provider delay. That measures the harness (Kafka, workers, Postgres, tools)
 * without spending tokens or hitting a provider's rate limit.
 */
@Component
@ConditionalOnProperty(name = "llm.mode", havingValue = "fake")
public class FakeLlmClient implements ProviderLlmClient {

    private static final List<String[]> SCRIPT = List.of(
            new String[] {"get_application_stats", "{\"from\":\"2026-10-15 06:00\",\"to\":\"2026-10-15 12:00\"}"},
            new String[] {"search_logs", "{\"from\":\"2026-10-15 10:00\",\"to\":\"2026-10-15 11:00\",\"level\":\"ERROR\",\"service\":\"kyc-service\"}"},
            new String[] {"get_recent_deploys", "{\"from\":\"2026-10-15 09:00\",\"to\":\"2026-10-15 12:00\"}"});

    private final Duration latency;

    public FakeLlmClient(@Value("${llm.fake.latency:300ms}") Duration latency) {
        this.latency = latency;
    }

    @Override
    public LlmResponse chat(List<ChatMessage> messages, List<ToolSpec> tools) {
        sleep();
        int promptTokens = messages.stream().mapToInt(m -> m.content() == null ? 20 : m.content().length() / 4).sum();
        long done = messages.stream().filter(m -> m.role().equals("assistant")).count();
        if (done < SCRIPT.size()) {
            String[] step = SCRIPT.get((int) done);
            ToolCall call = new ToolCall("fake_" + UUID.randomUUID(), "function", new ToolCall.Function(step[0], step[1]));
            return new LlmResponse(new ChatMessage("assistant", null, List.of(call), null), promptTokens, 40);
        }
        return new LlmResponse(new ChatMessage("assistant",
                "Root cause: kyc-service 2.3.0 deployed at 10:05 threw NullPointerException in PanValidator.\n"
                        + "Evidence: 139 errors from 10:05:30 to 10:51:30; rollback to 2.2.4 at 10:52.\n"
                        + "Impact: 68 of 120 applications failed between 10:00 and 11:00.\n"
                        + "Suggested fix: fix the null check in PanValidator before redeploying.", null, null),
                promptTokens, 120);
    }

    private void sleep() {
        try {
            Thread.sleep(latency.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
