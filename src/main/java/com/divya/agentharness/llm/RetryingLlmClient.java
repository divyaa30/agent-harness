package com.divya.agentharness.llm;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Wraps the real client and retries temporary failures (rate limits, server errors, timeouts)
 * with exponential backoff and jitter. Permanent failures (bad key, bad request) fail at once.
 *
 * It implements the same LlmClient interface, so callers can't tell it's there (decorator pattern).
 * {@code @Primary} makes Spring inject this one wherever an LlmClient is needed.
 */
@Primary
@Component
public class RetryingLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(RetryingLlmClient.class);

    /** Groq says e.g. "Please try again in 764.999999ms" or "try again in 1.5s". */
    private static final Pattern SUGGESTED_WAIT = Pattern.compile("try again in ([0-9.]+)(ms|s)");

    private final LlmClient delegate;
    private final int maxAttempts;
    private final Duration initialBackoff;
    private final Duration maxBackoff;

    public RetryingLlmClient(OpenAiCompatibleLlmClient delegate,
                             @Value("${llm.retry.max-attempts:5}") int maxAttempts,
                             @Value("${llm.retry.initial-backoff:1s}") Duration initialBackoff,
                             @Value("${llm.retry.max-backoff:30s}") Duration maxBackoff) {
        this.delegate = delegate;
        this.maxAttempts = maxAttempts;
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff;
    }

    @Override
    public LlmResponse chat(List<ChatMessage> messages, List<ToolSpec> tools) {
        for (int attempt = 1; ; attempt++) {
            try {
                return delegate.chat(messages, tools);
            } catch (RuntimeException e) {
                if (!isTemporary(e) || attempt >= maxAttempts) {
                    throw e;
                }
                Duration wait = waitBeforeRetry(attempt, e);
                log.warn("LLM call failed ({}), attempt {}/{}; retrying in {} ms",
                        shortReason(e), attempt, maxAttempts, wait.toMillis());
                sleep(wait);
            }
        }
    }

    /** Errors worth retrying: they usually go away by themselves. */
    static boolean isTemporary(RuntimeException e) {
        return e instanceof HttpClientErrorException.TooManyRequests   // 429: rate limit
                || e instanceof HttpServerErrorException                // 5xx: provider trouble
                || e instanceof ResourceAccessException;                // timeout or network error
    }

    /**
     * Exponential backoff (1s, 2s, 4s, ... capped), never shorter than the wait the provider
     * suggests, plus random jitter so many runs don't all retry at the same instant.
     */
    Duration waitBeforeRetry(int attempt, RuntimeException e) {
        long exponential = initialBackoff.toMillis() * (1L << (attempt - 1));
        long wait = Math.min(Math.max(exponential, suggestedWaitMillis(e)), maxBackoff.toMillis());
        return Duration.ofMillis(wait + ThreadLocalRandom.current().nextLong(250));
    }

    /** How long the provider asked us to wait, from the Retry-After header or the error text; 0 if unknown. */
    static long suggestedWaitMillis(RuntimeException e) {
        if (!(e instanceof HttpStatusCodeException http)) {
            return 0;
        }
        String retryAfter = http.getResponseHeaders() == null ? null : http.getResponseHeaders().getFirst("Retry-After");
        if (retryAfter != null && retryAfter.matches("[0-9]+")) {
            return Long.parseLong(retryAfter) * 1000;
        }
        Matcher m = SUGGESTED_WAIT.matcher(http.getResponseBodyAsString());
        if (m.find()) {
            double amount = Double.parseDouble(m.group(1));
            return (long) Math.ceil(m.group(2).equals("s") ? amount * 1000 : amount);
        }
        return 0;
    }

    private static String shortReason(RuntimeException e) {
        return e instanceof HttpStatusCodeException http ? String.valueOf(http.getStatusCode().value())
                : e.getClass().getSimpleName();
    }

    private static void sleep(Duration wait) {
        try {
            Thread.sleep(wait.toMillis());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry", ie);
        }
    }
}
