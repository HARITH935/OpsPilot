package com.opspilot.inventory.service;

import com.opspilot.inventory.model.Product;
import com.opspilot.inventory.model.Reservation;
import com.opspilot.inventory.model.ReservationStatus;
import com.opspilot.inventory.repository.ProductRepository;
import com.opspilot.inventory.repository.ReservationRepository;
import com.opspilot.inventory.service.ReservationResult.Reason;
import com.opspilot.inventory.service.ReservationResult.Rejected;
import com.opspilot.inventory.service.ReservationResult.Reserved;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Holds stock for orders while they are paid for, then commits it (paid) or releases it (not paid).
 * Called by the order saga's Kafka events from Phase 2.
 *
 * <p>Two orders may change the same product at the same moment. The product's @Version makes the slower one fail
 * (OptimisticLockingFailureException) instead of overwriting the other. @Retryable then runs the whole method again,
 * in a new transaction with fresh data: up to 5 retries, waiting about 10, 20, 40, 80, 160 ms, each nudged up or
 * down by a random "jitter" so the competing orders spread out instead of colliding again.
 */
@Service
@Retryable(includes = OptimisticLockingFailureException.class, maxRetries = 5, delay = 10, multiplier = 2, jitter = 5)
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    private final ProductRepository productRepository;
    private final ReservationRepository reservationRepository;
    private final TransactionOperations transactions;

    public ReservationService(ProductRepository productRepository, ReservationRepository reservationRepository,
                              TransactionOperations transactions) {
        this.productRepository = productRepository;
        this.reservationRepository = reservationRepository;
        this.transactions = transactions;
    }

    /**
     * All or nothing: either every item is held (Reserved, with prices), or none is (Rejected, naming the first
     * product, in SKU order, that is unknown or short). Holding the same order twice is refused.
     */
    public ReservationResult reserve(UUID orderId, List<RequestedItem> items) {
        return transactions.execute(tx -> tryReserve(orderId, items));   // one transaction per attempt
    }

    // Payment completed: the held units leave the warehouse. Returns how many reservations changed (0 on a repeat).
    public int commit(UUID orderId) {
        return transactions.execute(tx -> settle(orderId, true));
    }

    // Payment failed or the order was cancelled: the held units can be sold again. Returns 0 on a repeat.
    public int release(UUID orderId) {
        return transactions.execute(tx -> settle(orderId, false));
    }

    private ReservationResult tryReserve(UUID orderId, List<RequestedItem> items) {
        if (reservationRepository.existsByOrderId(orderId)) {
            throw new IllegalStateException("Stock for order " + orderId + " is already reserved");
        }

        // The same SKU twice in one order becomes one line with the quantities added up; TreeMap keeps SKU order
        Map<String, Integer> wanted = new TreeMap<>();
        items.forEach(item -> wanted.merge(item.sku(), item.qty(), Integer::sum));
        Map<String, Product> products = productsBySku(wanted.keySet());

        // 1) Check every line before changing anything, so a rejection never leaves a partial reservation behind
        for (Map.Entry<String, Integer> line : wanted.entrySet()) {
            Product product = products.get(line.getKey());
            if (product == null) {
                return rejected(orderId, line.getKey(), Reason.UNKNOWN_PRODUCT);
            }
            if (product.getAvailable() < line.getValue()) {
                return rejected(orderId, line.getKey(), Reason.OUT_OF_STOCK);
            }
        }

        // 2) Everything is available: hold it and price it
        List<Reserved.Item> reserved = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<String, Integer> line : wanted.entrySet()) {
            Product product = products.get(line.getKey());
            int qty = line.getValue();
            product.reserve(qty);
            reservationRepository.save(new Reservation(orderId, product.getSku(), qty));
            reserved.add(new Reserved.Item(product.getSku(), qty, product.getPrice()));
            total = total.add(product.getPrice().multiply(BigDecimal.valueOf(qty)));
        }
        // Write now, inside this attempt: if another order changed one of these products, the version check fails
        // here and @Retryable starts a fresh attempt
        productRepository.flush();

        log.info("Reserved {} product(s) for order {}, total {}", reserved.size(), orderId, total);
        return new Reserved(orderId, List.copyOf(reserved), total);
    }

    private int settle(UUID orderId, boolean paid) {
        List<Reservation> held = reservationRepository.findByOrderIdAndStatus(orderId, ReservationStatus.HELD);
        if (held.isEmpty()) {
            return 0;   // never reserved, rejected, or already settled: repeating a commit / release is harmless
        }

        Map<String, Product> products = productsBySku(held.stream().map(Reservation::getSku).toList());
        for (Reservation reservation : held) {
            Product product = products.get(reservation.getSku());   // always there: the foreign key guarantees it
            if (paid) {
                product.commit(reservation.getQty());
                reservation.commit();
            } else {
                product.release(reservation.getQty());
                reservation.release();
            }
        }
        productRepository.flush();   // same as in tryReserve: a concurrent change fails here and is retried

        log.info("{} {} reservation(s) for order {}", paid ? "Committed" : "Released", held.size(), orderId);
        return held.size();
    }

    // One query (WHERE sku IN ...) instead of one per product
    private Map<String, Product> productsBySku(Collection<String> skus) {
        return productRepository.findAllById(skus).stream()
                .collect(Collectors.toMap(Product::getSku, Function.identity()));
    }

    private static Rejected rejected(UUID orderId, String sku, Reason reason) {
        log.info("Order {} rejected: {} is {}", orderId, sku, reason);
        return new Rejected(orderId, sku, reason);
    }
}
