package com.opspilot.order.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Turns every error into an RFC 9457 ProblemDetail (application/problem+json).
 * The parent class already handles Spring's own errors: bad JSON, wrong types, unknown URLs, wrong content type.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(OrderNotFoundException.class)
    ProblemDetail handleOrderNotFound(OrderNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Order not found");
        return problem;
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    ProblemDetail handleIdempotencyKeyReused(IdempotencyKeyReusedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
        problem.setTitle("Idempotency-Key reused");
        return problem;
    }

    // Invalid @Valid @RequestBody on a method WITHOUT other constraints: add "errors": {field → message}
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, String> errors = new TreeMap<>();   // sorted by name: stable, readable output
        ex.getBindingResult().getFieldErrors().forEach(e -> addError(errors, e.getField(), e.getDefaultMessage()));

        ProblemDetail problem = ex.getBody();
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    // Raised instead of the one above when the method also has constraints on headers / query params (e.g. @Max on size).
    // Produces the same "errors" shape, so clients see one format.
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, String> errors = new TreeMap<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            if (result instanceof ParameterErrors bodyErrors) {   // a @Valid @RequestBody: one entry per invalid field
                bodyErrors.getFieldErrors().forEach(e -> addError(errors, e.getField(), e.getDefaultMessage()));
            } else {                                               // a header or query parameter
                String name = requestName(result.getMethodParameter());
                result.getResolvableErrors().forEach(e -> addError(errors, name, e.getDefaultMessage()));
            }
        }

        ProblemDetail problem = ex.getBody();
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    // Last resort: never leak stack traces or internals to clients, but log everything for us
    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error");
    }

    private static void addError(Map<String, String> errors, String name, String message) {
        errors.merge(name, Objects.requireNonNullElse(message, "is invalid"), (first, second) -> first + "; " + second);
    }

    // The name the client actually sent: "Idempotency-Key" for the header, "size" for ?size=
    private static String requestName(MethodParameter parameter) {
        RequestHeader header = parameter.getParameterAnnotation(RequestHeader.class);
        return header != null && !header.name().isEmpty() ? header.name() : parameter.getParameterName();
    }
}
