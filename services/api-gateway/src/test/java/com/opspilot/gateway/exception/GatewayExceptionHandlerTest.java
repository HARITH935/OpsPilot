package com.opspilot.gateway.exception;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

// Plain unit test: which failures count as "too slow" (504) and which as "not reachable" (503)
class GatewayExceptionHandlerTest {

    @Test
    void noAnswerInTimeIsTooSlow() {
        assertThat(GatewayExceptionHandler.answeredTooSlowly(failure(new HttpTimeoutException("request timed out"))))
                .isTrue();
    }

    @Test
    void notAcceptingTheConnectionInTimeIsNotReachable() {
        // HttpConnectTimeoutException is a kind of HttpTimeoutException, but the service never got the request
        assertThat(GatewayExceptionHandler.answeredTooSlowly(failure(new HttpConnectTimeoutException("connect timed out"))))
                .isFalse();
    }

    @Test
    void aRefusedConnectionIsNotReachable() {
        assertThat(GatewayExceptionHandler.answeredTooSlowly(failure(new ConnectException("Connection refused"))))
                .isFalse();
    }

    // What the HTTP client throws: the real cause wrapped in Spring's ResourceAccessException
    private static ResourceAccessException failure(IOException cause) {
        return new ResourceAccessException("I/O error on GET request for \"http://order-service/api/orders\"", cause);
    }
}
