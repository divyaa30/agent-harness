package com.divya.agentharness.run;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Safety net for the gap between saving a run and sending it to Kafka: if the app dies in
 * between, the run sits at PENDING and no worker ever hears about it. Every minute, this
 * re-sends any run that has been PENDING for too long.
 *
 * (The fully transactional alternative is the outbox pattern; this is the simpler version.)
 */
@Component
public class RunSweeper {

    private final AgentRunRepository runs;
    private final RunService runService;
    private final Duration pendingTimeout;

    public RunSweeper(AgentRunRepository runs, RunService runService,
                      @Value("${agent.sweeper.pending-timeout:2m}") Duration pendingTimeout) {
        this.runs = runs;
        this.runService = runService;
        this.pendingTimeout = pendingTimeout;
    }

    @Scheduled(fixedDelayString = "${agent.sweeper.interval-ms:60000}", initialDelayString = "${agent.sweeper.interval-ms:60000}")
    public void requeueStuckRuns() {
        List<AgentRun> stuck = runs.findTop20ByStatusAndUpdatedAtBeforeOrderByUpdatedAt(
                RunStatus.PENDING, Instant.now().minus(pendingTimeout));
        stuck.forEach(runService::requeue);
    }
}
