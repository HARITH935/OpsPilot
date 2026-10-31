package com.opspilot.order.service;

import com.opspilot.order.dto.CreateOrderRequest;
import com.opspilot.order.dto.OrderItemRequest;
import com.opspilot.order.dto.OrderResponse;
import com.opspilot.order.dto.OrderSummaryResponse;
import com.opspilot.order.dto.PageResponse;
import com.opspilot.order.exception.IdempotencyKeyReusedException;
import com.opspilot.order.exception.OrderNotFoundException;
import com.opspilot.order.model.Order;
import com.opspilot.order.model.OrderStatus;
import com.opspilot.order.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.support.TransactionOperations;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Pure unit test: the repository is a Mockito mock, so there is no Spring and no database
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    private static final CreateOrderRequest REQUEST = new CreateOrderRequest("c-1", List.of(
            new OrderItemRequest("SKU-1", 2),
            new OrderItemRequest("SKU-2", 1)));

    @Mock
    private OrderRepository orderRepository;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        // withoutTransaction(): just runs the code — there is no database in this test
        orderService = new OrderService(orderRepository, TransactionOperations.withoutTransaction());
    }

    @Test
    void createOrderWithoutKeySavesPendingOrderWithAllItems() {
        CreateOrderResult result = orderService.createOrder(REQUEST, null);

        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);   // catches the Order given to saveAndFlush()
        verify(orderRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(result.orderId());
        assertThat(saved.getValue().getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(saved.getValue().getIdempotencyKey()).isNull();
        assertThat(saved.getValue().getItems())
                .extracting("sku", "qty")
                .containsExactly(tuple("SKU-1", 2), tuple("SKU-2", 1));
        assertThat(result.replayed()).isFalse();
        verify(orderRepository, never()).findByIdempotencyKey(any());
    }

    @Test
    void createOrderWithNewKeyStoresKeyAndFingerprint() {
        when(orderRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.empty());

        CreateOrderResult result = orderService.createOrder(REQUEST, "key-1");

        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getIdempotencyKey()).isEqualTo("key-1");
        assertThat(saved.getValue().getRequestFingerprint()).isEqualTo(RequestFingerprint.of(REQUEST));
        assertThat(result.replayed()).isFalse();
    }

    @Test
    void sameKeyAndSameRequestReturnsTheOriginalOrderWithoutSaving() {
        Order original = new Order("c-1", "key-1", RequestFingerprint.of(REQUEST));
        when(orderRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(original));

        CreateOrderResult result = orderService.createOrder(REQUEST, "key-1");

        assertThat(result.orderId()).isEqualTo(original.getId());
        assertThat(result.replayed()).isTrue();
        verify(orderRepository, never()).saveAndFlush(any());
    }

    @Test
    void sameKeyWithADifferentRequestIsRejected() {
        Order original = new Order("c-1", "key-1", "fingerprint-of-another-request");
        when(orderRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(original));

        assertThatThrownBy(() -> orderService.createOrder(REQUEST, "key-1"))
                .isInstanceOf(IdempotencyKeyReusedException.class);
        verify(orderRepository, never()).saveAndFlush(any());
    }

    @Test
    void losingAConcurrentRaceReturnsTheWinnersOrder() {
        Order winner = new Order("c-1", "key-1", RequestFingerprint.of(REQUEST));
        when(orderRepository.findByIdempotencyKey("key-1"))
                .thenReturn(Optional.empty())        // 1st look: the other request hasn't committed yet
                .thenReturn(Optional.of(winner));    // after our insert failed: the other request's order
        when(orderRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));

        CreateOrderResult result = orderService.createOrder(REQUEST, "key-1");

        assertThat(result.orderId()).isEqualTo(winner.getId());
        assertThat(result.replayed()).isTrue();
    }

    @Test
    void listOrdersFiltersByStatusNewestFirst() {
        Order order = new Order("c-1");
        when(orderRepository.findByStatus(eq(OrderStatus.PENDING), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(order), PageRequest.of(0, 20), 1));

        PageResponse<OrderSummaryResponse> page = orderService.listOrders(OrderStatus.PENDING, 0, 20);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(orderRepository).findByStatus(eq(OrderStatus.PENDING), pageable.capture());
        assertThat(pageable.getValue().getSort().getOrderFor("createdAt").getDirection())
                .isEqualTo(Sort.Direction.DESC);
        assertThat(page.content()).extracting(OrderSummaryResponse::id).containsExactly(order.getId());
        assertThat(page.totalElements()).isEqualTo(1);
    }

    @Test
    void listOrdersWithoutStatusReturnsAllOrders() {
        when(orderRepository.findAll(any(Pageable.class))).thenReturn(Page.empty());

        orderService.listOrders(null, 0, 20);

        verify(orderRepository).findAll(any(Pageable.class));
        verify(orderRepository, never()).findByStatus(any(), any());
    }

    @Test
    void getOrderReturnsTheOrderAsAResponse() {
        Order order = new Order("c-1");
        order.addItem("SKU-1", 2);
        when(orderRepository.findWithItemsById(order.getId())).thenReturn(Optional.of(order));

        OrderResponse response = orderService.getOrder(order.getId());

        assertThat(response.id()).isEqualTo(order.getId());
        assertThat(response.customerId()).isEqualTo("c-1");
        assertThat(response.totalAmount()).isNull();   // not priced yet — inventory prices it in the saga (ADR 0004)
        assertThat(response.items()).extracting("sku", "qty").containsExactly(tuple("SKU-1", 2));
    }

    @Test
    void getOrderThrowsWhenTheOrderDoesNotExist() {
        UUID id = UUID.randomUUID();
        when(orderRepository.findWithItemsById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.getOrder(id))
                .isInstanceOf(OrderNotFoundException.class)
                .hasMessageContaining(id.toString());
    }
}
