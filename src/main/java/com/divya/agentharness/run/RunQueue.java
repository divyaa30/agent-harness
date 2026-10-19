package com.divya.agentharness.run;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Puts "please investigate run X" on the Kafka to-do list.
 */
@Component
public class RunQueue {

    private final KafkaTemplate<String, String> kafka;

    public RunQueue(KafkaTemplate<String, String> kafka) {
        this.kafka = kafka;
    }

    /**
     * Waits until Kafka confirms it stored the message, so callers never report "queued"
     * for a run no worker will ever see. The run id is also the message key, so all
     * messages for one run land in the same partition, in order.
     */
    public void send(UUID runId) throws Exception {
        String id = runId.toString();
        kafka.send(KafkaTopics.AGENT_RUNS, id, id).get(5, TimeUnit.SECONDS);
    }
}
