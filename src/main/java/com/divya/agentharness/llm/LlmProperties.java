package com.divya.agentharness.llm;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The "llm:" section of application.yml, loaded into a Java object by Spring.
 */
@ConfigurationProperties(prefix = "llm")
public record LlmProperties(String baseUrl, String model, String apiKey, Duration timeout) {
}
