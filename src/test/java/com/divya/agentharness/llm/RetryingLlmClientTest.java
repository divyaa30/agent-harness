package com.divya.agentharness.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

class RetryingLlmClientTest {

    private final OpenAiCompatibleLlmClient delegate = mock(OpenAiCompatibleLlmClient.class);
    private final RetryingLlmClient client = new RetryingLlmClient(delegate, 3, Duration.ofMillis(1), Duration.ofMillis(5));
    private final LlmResponse ok = new LlmResponse(new ChatMessage("assistant", "ok", null, null), 1, 1);

    @Test
    void retriesARateLimitAndThenSucceeds() {
        when(delegate.chat(any(), any())).thenThrow(error(HttpStatus.TOO_MANY_REQUESTS, "slow down"))
                .thenThrow(error(HttpStatus.TOO_MANY_REQUESTS, "slow down"))
                .thenReturn(ok);

        assertThat(client.chat(List.of(), List.of())).isSameAs(ok);
        verify(delegate, times(3)).chat(any(), any());
    }

    @Test
    void retriesServerErrorsAndTimeouts() {
        when(delegate.chat(any(), any()))
                .thenThrow(HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "down", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8))
                .thenThrow(new ResourceAccessException("read timed out"))
                .thenReturn(ok);

        assertThat(client.chat(List.of(), List.of())).isSameAs(ok);
    }

    @Test
    void doesNotRetryPermanentErrors() {
        when(delegate.chat(any(), any())).thenThrow(error(HttpStatus.UNAUTHORIZED, "bad key"));

        assertThatThrownBy(() -> client.chat(List.of(), List.of())).isInstanceOf(HttpClientErrorException.Unauthorized.class);
        verify(delegate, times(1)).chat(any(), any());
    }

    @Test
    void givesUpAfterTheLastAttempt() {
        when(delegate.chat(any(), any())).thenThrow(error(HttpStatus.TOO_MANY_REQUESTS, "slow down"));

        assertThatThrownBy(() -> client.chat(List.of(), List.of())).isInstanceOf(HttpClientErrorException.TooManyRequests.class);
        verify(delegate, times(3)).chat(any(), any());
    }

    @Test
    void readsHowLongTheProviderAsksUsToWait() {
        assertThat(RetryingLlmClient.suggestedWaitMillis(error(HttpStatus.TOO_MANY_REQUESTS,
                "Rate limit reached. Please try again in 764.999999ms."))).isEqualTo(765);
        assertThat(RetryingLlmClient.suggestedWaitMillis(error(HttpStatus.TOO_MANY_REQUESTS,
                "Please try again in 1.5s"))).isEqualTo(1500);

        HttpHeaders headers = new HttpHeaders();
        headers.add("Retry-After", "3");
        assertThat(RetryingLlmClient.suggestedWaitMillis(HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS,
                "Too Many Requests", headers, new byte[0], StandardCharsets.UTF_8))).isEqualTo(3000);
    }

    @Test
    void backoffDoublesButNeverWaitsLessThanTheProviderAsks() {
        RetryingLlmClient slow = new RetryingLlmClient(delegate, 5, Duration.ofSeconds(1), Duration.ofSeconds(30));
        RuntimeException noHint = error(HttpStatus.TOO_MANY_REQUESTS, "slow down");

        assertThat(slow.waitBeforeRetry(1, noHint).toMillis()).isBetween(1000L, 1250L);
        assertThat(slow.waitBeforeRetry(3, noHint).toMillis()).isBetween(4000L, 4250L);
        assertThat(slow.waitBeforeRetry(1, error(HttpStatus.TOO_MANY_REQUESTS, "try again in 8s")).toMillis())
                .isBetween(8000L, 8250L);
        assertThat(slow.waitBeforeRetry(10, noHint).toMillis()).isBetween(30000L, 30250L);  // capped
    }

    private static HttpClientErrorException error(HttpStatus status, String body) {
        return HttpClientErrorException.create(status, status.getReasonPhrase(), HttpHeaders.EMPTY,
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }
}
