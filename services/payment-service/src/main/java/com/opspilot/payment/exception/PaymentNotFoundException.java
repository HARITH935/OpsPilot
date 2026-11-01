package com.opspilot.payment.exception;

import java.util.UUID;

public class PaymentNotFoundException extends RuntimeException {

    public PaymentNotFoundException(UUID id) {
        super("Payment " + id + " does not exist");
    }
}
