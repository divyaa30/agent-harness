package com.divya.agentharness.run;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Reads "please investigate run X" messages from Kafka and does the investigation.
 *
 * The offset (bookmark) is committed only after this method returns (ack-mode: record).
 * If the app crashes halfway, the offset is never committed, Kafka delivers the message
 * again on restart, and processing resumes from the steps already saved in Postgres.
 */
@Component
public class RunWorker {

    private static final Logger log = LoggerFactory.getLogger(RunWorker.class);

    private final RunService runService;

    public RunWorker(RunService runService) {
        this.runService = runService;
    }

    @KafkaListener(topics = KafkaTopics.AGENT_RUNS)
    public void onRunRequested(String runId) {
        log.info("Worker picked up run {}", runId);
        runService.process(UUID.fromString(runId));
        log.info("Worker finished run {}; committing offset", runId);
    }
}
