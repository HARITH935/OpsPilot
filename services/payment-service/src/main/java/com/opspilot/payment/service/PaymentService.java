package com.opspilot.payment.service;

import com.opspilot.payment.dto.PageResponse;
import com.opspilot.payment.dto.PaymentResponse;
import com.opspilot.payment.exception.PaymentNotFoundException;
import com.opspilot.payment.exception.PaymentProviderException;
import com.opspilot.payment.model.Payment;
import com.opspilot.payment.model.PaymentStatus;
import com.opspilot.payment.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

@Service
public class PaymentService {

    static final String PROVIDER_ERROR = "PROVIDER_ERROR";   // failure_reason when the provider fails

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository paymentRepository;
    private final PaymentProvider paymentProvider;

    public PaymentService(PaymentRepository paymentRepository, PaymentProvider paymentProvider) {
        this.paymentRepository = paymentRepository;
        this.paymentProvider = paymentProvider;
    }

    /**
     * Charges an order once. If the order already has a payment, that one is returned and the provider is not called
     * again. Called by the order saga (StockReserved event) from Phase 2.
     *
     * <p>Deliberately not @Transactional: the provider call can be slow (PAYMENT_LATENCY_MS). Holding a transaction,
     * and with it a database connection, while waiting would let a slow provider use up the connection pool.
     */
    public PaymentResponse charge(UUID orderId, BigDecimal amount) {
        Optional<Payment> existing = paymentRepository.findByOrderId(orderId);
        if (existing.isPresent()) {
            log.info("Order {} already has a {} payment; not charging again", orderId, existing.get().getStatus());
            return PaymentResponse.from(existing.get());
        }

        Payment payment;
        try {
            paymentProvider.charge(orderId, amount);
            payment = Payment.completed(orderId, amount);
        } catch (PaymentProviderException e) {
            log.warn("Payment for order {} failed: {}", orderId, e.getMessage());
            payment = Payment.failed(orderId, amount, PROVIDER_ERROR);
        }

        try {
            paymentRepository.saveAndFlush(payment);   // runs in its own short transaction (Spring Data's default)
        } catch (DataIntegrityViolationException e) {
            // Race: another charge for this order was stored first; the unique order_id let only one in.
            // (Both reached the provider. A real provider would drop the repeat by the order id; with Kafka in
            // Phase 2 one order's events are handled one at a time, so this doesn't happen.)
            log.info("Order {} was charged concurrently; returning the payment stored first", orderId);
            return paymentRepository.findByOrderId(orderId).map(PaymentResponse::from).orElseThrow(() -> e);
        }

        log.info("Payment {} for order {}: {} {}", payment.getId(), orderId, payment.getStatus(), amount);
        return PaymentResponse.from(payment);
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPayment(UUID id) {
        return paymentRepository.findById(id)
                .map(PaymentResponse::from)
                .orElseThrow(() -> new PaymentNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<PaymentResponse> listPayments(PaymentStatus status, int page, int size) {
        // Newest first; the id breaks ties so pages stay stable when two payments share a timestamp
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        Page<Payment> payments = status == null
                ? paymentRepository.findAll(pageable)
                : paymentRepository.findByStatus(status, pageable);
        return PageResponse.from(payments.map(PaymentResponse::from));
    }
}
