package com.opspilot.payment.service;

import java.math.BigDecimal;
import java.util.UUID;

// The outside payment company. Only a simulated one exists here; a real one would call the company's HTTP API.
public interface PaymentProvider {

    // Takes the money, or throws PaymentProviderException if the provider fails
    void charge(UUID orderId, BigDecimal amount);

    // Gives the money back, or throws PaymentProviderException if the provider fails
    void refund(UUID orderId, BigDecimal amount);
}
