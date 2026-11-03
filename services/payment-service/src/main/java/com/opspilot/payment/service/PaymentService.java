package com.opspilot.payment.service;

import com.opspilot.events.EventPayload;
import com.opspilot.events.PaymentCompleted;
import com.opspilot.events.PaymentFailed;
import com.opspilot.events.PaymentRefunded;
import com.opspilot.messaging.consumer.ProcessedEvents;
import com.opspilot.messaging.outbox.OutboxWriter;
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
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

@Service
public class PaymentService {

    static final String PROVIDER_ERROR = "PROVIDER_ERROR";   // failure_reason when the provider fails

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository paymentRepository;
    private final PaymentProvider paymentProvider;
    private final ProcessedEvents processedEvents;
    private final OutboxWriter outbox;
    private final TransactionOperations transactions;

    public PaymentService(PaymentRepository paymentRepository, PaymentProvider paymentProvider,
                          ProcessedEvents processedEvents, OutboxWriter outbox, TransactionOperations transactions) {
        this.paymentRepository = paymentRepository;
        this.paymentProvider = paymentProvider;
        this.processedEvents = processedEvents;
        this.outbox = outbox;
        this.transactions = transactions;
    }

    /**
     * Charges an order once, for the StockReserved event {@code eventId}. If the order already has a payment (the same
     * event delivered again), that one is returned and the provider is not called again. Otherwise the payment, the
     * event's duplicate mark and the answer (PaymentCompleted / PaymentFailed, via the outbox) are saved in one
     * transaction.
     *
     * <p>Deliberately not @Transactional: the provider call can be slow (PAYMENT_LATENCY_MS). Holding a transaction,
     * and with it a database connection, while waiting would let a slow provider use up the connection pool.
     */
    public PaymentResponse charge(UUID eventId, UUID orderId, BigDecimal amount) {
        Optional<Payment> existing = paymentRepository.findByOrderId(orderId);
        if (existing.isPresent()) {
            log.info("Order {} already has a {} payment; not charging again", orderId, existing.get().getStatus());
            return PaymentResponse.from(existing.get());
        }

        Payment payment = callProvider(orderId, amount);   // outside any transaction
        try {
            Boolean saved = transactions.execute(tx -> {   // short: only database work, the provider call is done
                if (!processedEvents.markProcessed(eventId)) {
                    return false;   // another delivery of this event finished in the meantime
                }
                paymentRepository.saveAndFlush(payment);   // flush now: a second payment for the order fails here
                outbox.write(answer(payment));             // the saga's next step: order and inventory react to it
                return true;
            });
            if (!Boolean.TRUE.equals(saved)) {
                log.info("Event {} for order {} was already handled, skipping the duplicate", eventId, orderId);
                return paymentRepository.findByOrderId(orderId).map(PaymentResponse::from).orElseThrow();
            }
        } catch (DataIntegrityViolationException e) {
            // Race: another charge for this order was stored first; the unique order_id let only one in.
            // (Both reached the provider. A real provider would drop the repeat by the order id; with Kafka one
            // order's events are handled one at a time, so this only happens if a consumer hand-over overlaps.)
            log.info("Order {} was charged concurrently; returning the payment stored first", orderId);
            return paymentRepository.findByOrderId(orderId).map(PaymentResponse::from).orElseThrow(() -> e);
        }

        log.info("Payment {} for order {}: {} {}", payment.getId(), orderId, payment.getStatus(), amount);
        return PaymentResponse.from(payment);
    }

    /**
     * Refunds a payment once, for the RefundRequested event {@code eventId}. order-service asks for it when the payment
     * completed after the saga timeout had already cancelled the order (ADR 0006). If the payment is already refunded
     * (the same request delivered again), it is returned and the provider is not called again. Otherwise the provider
     * gives back the amount stored with the payment, outside any transaction (as in charge). Then one transaction saves
     * the event's duplicate mark and the REFUNDED payment, and writes PaymentRefunded to the outbox.
     *
     * <p>If the provider refuses, PaymentProviderException goes up to the Kafka error handler and nothing is saved:
     * the request is retried, then parked in orders.events.dlq for a human.
     */
    public PaymentResponse refund(UUID eventId, UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId).orElseThrow(() -> new PaymentNotFoundException(paymentId));
        if (payment.getStatus() == PaymentStatus.REFUNDED) {
            log.info("Payment {} for order {} is already refunded; not refunding again", paymentId, payment.getOrderId());
            return PaymentResponse.from(payment);
        }
        payment.refund();   // COMPLETED → REFUNDED, in memory only for now; throws for a FAILED payment

        paymentProvider.refund(payment.getOrderId(), payment.getAmount());   // outside any transaction
        Boolean saved = transactions.execute(tx -> {   // short: only database work, the provider call is done
            if (!processedEvents.markProcessed(eventId)) {
                return false;   // another delivery of this request finished in the meantime
            }
            // The version read above must still be the current one: if another refund saved first, this fails
            paymentRepository.saveAndFlush(payment);
            outbox.write(new PaymentRefunded(payment.getOrderId(), payment.getId(), payment.getAmount()));
            return true;
        });
        if (!Boolean.TRUE.equals(saved)) {
            log.info("Event {} for payment {} was already handled, skipping the duplicate", eventId, paymentId);
            return paymentRepository.findById(paymentId).map(PaymentResponse::from).orElseThrow();
        }

        log.info("Payment {} for order {} refunded: {}", paymentId, payment.getOrderId(), payment.getAmount());
        return PaymentResponse.from(payment);
    }

    private Payment callProvider(UUID orderId, BigDecimal amount) {
        try {
            paymentProvider.charge(orderId, amount);
            return Payment.completed(orderId, amount);
        } catch (PaymentProviderException e) {
            log.warn("Payment for order {} failed: {}", orderId, e.getMessage());
            return Payment.failed(orderId, amount, PROVIDER_ERROR);
        }
    }

    private static EventPayload answer(Payment payment) {
        return payment.getStatus() == PaymentStatus.COMPLETED
                ? new PaymentCompleted(payment.getOrderId(), payment.getId(), payment.getAmount())
                : new PaymentFailed(payment.getOrderId(), payment.getId(), payment.getAmount(), payment.getFailureReason());
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
