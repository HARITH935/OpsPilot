package com.opspilot.inventory.service;

import com.opspilot.inventory.model.Product;
import com.opspilot.inventory.model.Reservation;
import com.opspilot.inventory.model.ReservationStatus;
import com.opspilot.inventory.repository.ProductRepository;
import com.opspilot.inventory.repository.ReservationRepository;
import com.opspilot.inventory.service.ReservationResult.Reason;
import com.opspilot.inventory.service.ReservationResult.Rejected;
import com.opspilot.inventory.service.ReservationResult.Reserved;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// Pure unit test: the repositories are Mockito mocks. Without Spring there is no @Retryable here;
// ReservationRetryTest covers the retries.
@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    private static final UUID ORDER = UUID.randomUUID();

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ReservationRepository reservationRepository;

    private ReservationService reservationService;

    @BeforeEach
    void setUp() {
        // withoutTransaction(): just runs the code — there is no database in this test
        reservationService = new ReservationService(
                productRepository, reservationRepository, TransactionOperations.withoutTransaction());
    }

    @Test
    void reserveHoldsEveryItemAndPricesTheOrder() {
        Product keyboard = product("SKU-1", "89.99", 10);
        Product mouse = product("SKU-2", "29.99", 5);
        when(productRepository.findAllById(any())).thenReturn(List.of(keyboard, mouse));

        ReservationResult result = reservationService.reserve(ORDER, List.of(item("SKU-2", 1), item("SKU-1", 2)));

        assertThat(result).isInstanceOfSatisfying(Reserved.class, reserved -> {
            assertThat(reserved.items()).containsExactly(   // in SKU order, priced from the catalogue
                    new Reserved.Item("SKU-1", 2, new BigDecimal("89.99")),
                    new Reserved.Item("SKU-2", 1, new BigDecimal("29.99")));
            assertThat(reserved.totalAmount()).isEqualByComparingTo("209.97");   // 2 × 89.99 + 29.99
        });
        assertThat(keyboard.getReserved()).isEqualTo(2);
        assertThat(mouse.getReserved()).isEqualTo(1);
        verify(reservationRepository, times(2)).save(any(Reservation.class));
        verify(productRepository).flush();
    }

    @Test
    void sameSkuTwiceInOneOrderIsHeldAsOneLine() {
        Product keyboard = product("SKU-1", "89.99", 10);
        when(productRepository.findAllById(any())).thenReturn(List.of(keyboard));

        reservationService.reserve(ORDER, List.of(item("SKU-1", 2), item("SKU-1", 3)));

        ArgumentCaptor<Reservation> saved = ArgumentCaptor.forClass(Reservation.class);   // catches what was saved
        verify(reservationRepository).save(saved.capture());   // one row, not two
        assertThat(saved.getValue().getQty()).isEqualTo(5);
        assertThat(keyboard.getReserved()).isEqualTo(5);
    }

    @Test
    void unknownProductRejectsTheWholeOrder() {
        Product keyboard = product("SKU-1", "89.99", 10);
        when(productRepository.findAllById(any())).thenReturn(List.of(keyboard));   // SKU-404 is not in the catalogue

        ReservationResult result = reservationService.reserve(ORDER, List.of(item("SKU-1", 1), item("SKU-404", 1)));

        assertThat(result).isEqualTo(new Rejected(ORDER, "SKU-404", Reason.UNKNOWN_PRODUCT));
        assertThat(keyboard.getReserved()).isZero();   // nothing held for the other item either
        verify(reservationRepository, never()).save(any());
    }

    @Test
    void notEnoughStockRejectsTheWholeOrder() {
        Product keyboard = product("SKU-1", "89.99", 10);
        Product mouse = product("SKU-2", "29.99", 1);
        when(productRepository.findAllById(any())).thenReturn(List.of(keyboard, mouse));

        ReservationResult result = reservationService.reserve(ORDER, List.of(item("SKU-1", 2), item("SKU-2", 3)));

        assertThat(result).isEqualTo(new Rejected(ORDER, "SKU-2", Reason.OUT_OF_STOCK));
        assertThat(keyboard.getReserved()).isZero();
        verify(reservationRepository, never()).save(any());
        verify(productRepository, never()).flush();
    }

    @Test
    void anOrderCannotBeReservedTwice() {
        when(reservationRepository.existsByOrderId(ORDER)).thenReturn(true);

        assertThatThrownBy(() -> reservationService.reserve(ORDER, List.of(item("SKU-1", 1))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already reserved");
        verify(productRepository, never()).findAllById(any());
    }

    @Test
    void commitTakesTheHeldUnitsOutOfStock() {
        Product keyboard = product("SKU-1", "89.99", 10);
        keyboard.reserve(2);
        Reservation reservation = new Reservation(ORDER, "SKU-1", 2);
        when(reservationRepository.findByOrderIdAndStatus(ORDER, ReservationStatus.HELD)).thenReturn(List.of(reservation));
        when(productRepository.findAllById(any())).thenReturn(List.of(keyboard));

        int changed = reservationService.commit(ORDER);

        assertThat(changed).isEqualTo(1);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.COMMITTED);
        assertThat(keyboard.getStock()).isEqualTo(8);
        assertThat(keyboard.getReserved()).isZero();
    }

    @Test
    void releasePutsTheHeldUnitsBackOnSale() {
        Product keyboard = product("SKU-1", "89.99", 10);
        keyboard.reserve(2);
        Reservation reservation = new Reservation(ORDER, "SKU-1", 2);
        when(reservationRepository.findByOrderIdAndStatus(ORDER, ReservationStatus.HELD)).thenReturn(List.of(reservation));
        when(productRepository.findAllById(any())).thenReturn(List.of(keyboard));

        int changed = reservationService.release(ORDER);

        assertThat(changed).isEqualTo(1);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(keyboard.getStock()).isEqualTo(10);
        assertThat(keyboard.getAvailable()).isEqualTo(10);
    }

    @Test
    void settlingAnOrderWithNothingHeldChangesNothing() {
        when(reservationRepository.findByOrderIdAndStatus(ORDER, ReservationStatus.HELD)).thenReturn(List.of());

        assertThat(reservationService.release(ORDER)).isZero();   // e.g. a rejected order, or a repeated release
        assertThat(reservationService.commit(ORDER)).isZero();
        verifyNoInteractions(productRepository);
    }

    private static Product product(String sku, String price, int stock) {
        return new Product(sku, "Product " + sku, new BigDecimal(price), stock);
    }

    private static RequestedItem item(String sku, int qty) {
        return new RequestedItem(sku, qty);
    }
}
