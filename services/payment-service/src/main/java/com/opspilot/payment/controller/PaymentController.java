package com.opspilot.payment.controller;

import com.opspilot.payment.dto.PageResponse;
import com.opspilot.payment.dto.PaymentResponse;
import com.opspilot.payment.model.PaymentStatus;
import com.opspilot.payment.service.PaymentService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// Read-only on purpose: payments are created only by the order saga (Kafka, Phase 2), never directly by a client
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    // GET /api/payments?status=FAILED&page=0&size=20 — every parameter is optional
    @GetMapping
    public PageResponse<PaymentResponse> listPayments(
            @RequestParam(required = false) PaymentStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {   // cap the page size: no "give me everything"
        return paymentService.listPayments(status, page, size);
    }

    @GetMapping("/{id}")   // a non-UUID id is rejected with 400 before this method runs
    public PaymentResponse getPayment(@PathVariable UUID id) {
        return paymentService.getPayment(id);
    }
}
