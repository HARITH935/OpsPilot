package com.opspilot.payment.exception;

// The payment provider failed to take (or give back) the money. Never sent to HTTP clients: for a charge, PaymentService
// stores a FAILED payment with reason() as its failure_reason.
public class PaymentProviderException extends RuntimeException {

    // The three reasons, as stored in payments.failure_reason and sent in PaymentFailed
    public static final String ERROR = "PROVIDER_ERROR";               // the provider said no: nothing was taken
    public static final String TIMEOUT = "PROVIDER_TIMEOUT";           // no answer in time (ResilientPaymentProvider)
    public static final String UNAVAILABLE = "PROVIDER_UNAVAILABLE";   // not even called: circuit open, or too busy

    private final String reason;

    public PaymentProviderException(String message) {
        this(ERROR, message, null);
    }

    public PaymentProviderException(String message, Throwable cause) {
        this(ERROR, message, cause);
    }

    public PaymentProviderException(String reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
