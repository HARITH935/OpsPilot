package com.opspilot.gateway.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;

import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;

/**
 * Turns "the service behind this route could not be reached" into an RFC 9457 ProblemDetail
 * (application/problem+json), in the same format as the services' own errors. Without it the client gets a bare 500.
 * An error the service itself returns (a 404, a 422, ...) is not handled here: the gateway passes it on unchanged.
 * Also a failed login. (401 / 403 for tokens and roles come from Spring Security: SecurityProblemHandler.)
 */
@RestControllerAdvice
public class GatewayExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayExceptionHandler.class);

    // Thrown by the gateway's HTTP client when there is no answer at all: refused, timed out, connection dropped
    @ExceptionHandler(ResourceAccessException.class)
    ProblemDetail handleServiceUnreachable(ResourceAccessException ex, HttpServletRequest request) {
        // The full message names the service's internal address: useful in our log, not for the public. The cause
        // says what actually happened (ConnectException = refused, HttpTimeoutException = too slow, ...).
        log.warn("{} {} failed ({}): {}", request.getMethod(), request.getRequestURI(),
                ex.getCause() == null ? "no cause" : ex.getCause().getClass().getSimpleName(), ex.getMessage());

        if (answeredTooSlowly(ex)) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.GATEWAY_TIMEOUT,
                    "The service behind " + request.getRequestURI() + " did not answer in time");
            problem.setTitle("Service too slow");
            return problem;
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "The service behind " + request.getRequestURI() + " can't be reached right now");
        problem.setTitle("Service unavailable");
        return problem;
    }

    // POST /auth/token with a wrong username or password
    @ExceptionHandler(InvalidCredentialsException.class)
    ProblemDetail handleInvalidCredentials(InvalidCredentialsException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
        problem.setTitle("Login failed");
        return problem;
    }

    // 504 only when the service accepted the request and then took too long to answer (read timeout). Not accepting
    // the connection in time (connect timeout, a subtype of HttpTimeoutException) means "not reachable": 503.
    static boolean answeredTooSlowly(Throwable ex) {
        return hasCause(ex, HttpTimeoutException.class) && !hasCause(ex, HttpConnectTimeoutException.class);
    }

    private static boolean hasCause(Throwable ex, Class<? extends Throwable> type) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }
}
