package com.divya.agentharness.run;

import java.time.Duration;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * The numbers worth watching on a dashboard, exposed at /actuator/prometheus.
 */
@Component
public class AgentMetrics {

    private final MeterRegistry registry;
    private final Timer endToEnd;
    private final Counter tokens;

    public AgentMetrics(MeterRegistry registry) {
        this.registry = registry;
        // From POST /runs to a final status: queue wait + investigation. Histogram for p50/p95/p99.
        this.endToEnd = Timer.builder("agent.run.duration")
                .description("Time from a run being accepted to it finishing")
                .publishPercentileHistogram()
                .register(registry);
        this.tokens = Counter.builder("agent.llm.tokens").description("Tokens used by model calls").register(registry);
    }

    /** A processing attempt ended: COMPLETED, FAILED or WAITING_APPROVAL. */
    public void runEnded(AgentRun run) {
        registry.counter("agent.runs", "outcome", run.getStatus().name()).increment();
        if (run.getStatus() == RunStatus.COMPLETED || run.getStatus() == RunStatus.FAILED) {
            endToEnd.record(Duration.between(run.getCreatedAt(), run.getUpdatedAt()));
        }
    }

    public void tokensUsed(int count) {
        tokens.increment(count);
    }

    public void toolRequested(String tool) {
        registry.counter("agent.tool.calls", "tool", tool).increment();
    }

    public void runResumed() {
        registry.counter("agent.runs.resumed").increment();
    }
}
