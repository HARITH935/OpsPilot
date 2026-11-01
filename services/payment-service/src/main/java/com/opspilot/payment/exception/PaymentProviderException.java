package com.opspilot.payment.exception;

// The payment provider failed to take the money. Never sent to HTTP clients: PaymentService stores a FAILED payment.
public class PaymentProviderException extends RuntimeException {

    public PaymentProviderException(String message) {
        super(message);
    }

    public PaymentProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
