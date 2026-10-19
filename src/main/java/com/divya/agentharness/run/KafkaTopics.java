package com.divya.agentharness.run;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Creates the Kafka topics the app needs on startup (if they don't exist yet).
 */
@Configuration
public class KafkaTopics {

    /** "Please investigate run X". The message key and value are both the run id. */
    public static final String AGENT_RUNS = "agent-runs";

    @Bean
    NewTopic agentRunsTopic() {
        // 3 partitions: up to 3 workers can process runs in parallel.
        // 1 replica: we only have one Kafka node locally (production would use 3).
        return TopicBuilder.name(AGENT_RUNS).partitions(3).replicas(1).build();
    }
}
