package com.opspilot.order.exception;

// The client sent an Idempotency-Key it already used, but with a different request body: a client bug → 422
public class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException(String idempotencyKey) {
        super("Idempotency-Key '" + idempotencyKey + "' was already used with a different request body");
    }
}
