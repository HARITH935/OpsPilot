package com.opspilot.order.service;

import com.opspilot.order.dto.CreateOrderRequest;
import com.opspilot.order.dto.CreateOrderResponse;
import com.opspilot.order.dto.OrderItemRequest;
import com.opspilot.order.dto.OrderResponse;
import com.opspilot.order.exception.OrderNotFoundException;
import com.opspilot.order.model.Order;
import com.opspilot.order.model.OrderStatus;
import com.opspilot.order.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Pure unit test: the repository is a Mockito mock, so there is no Spring and no database
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @InjectMocks   // creates OrderService and passes the mock repository into its constructor
    private OrderService orderService;

    @Test
    void createOrderSavesPendingOrderWithAllItems() {
        CreateOrderRequest request = new CreateOrderRequest("c-1", List.of(
                new OrderItemRequest("SKU-1", 2),
                new OrderItemRequest("SKU-2", 1)));

        CreateOrderResponse response = orderService.createOrder(request);

        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);   // catches the Order given to save()
        verify(orderRepository).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(response.orderId());
        assertThat(saved.getValue().getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(saved.getValue().getItems())
                .extracting("sku", "qty")
                .containsExactly(tuple("SKU-1", 2), tuple("SKU-2", 1));
        assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void getOrderReturnsTheOrderAsAResponse() {
        Order order = new Order("c-1", null);
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
