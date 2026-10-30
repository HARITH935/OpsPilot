package com.opspilot.order.service;

import com.opspilot.order.dto.CreateOrderRequest;
import com.opspilot.order.dto.CreateOrderResponse;
import com.opspilot.order.dto.OrderResponse;
import com.opspilot.order.exception.OrderNotFoundException;
import com.opspilot.order.model.Order;
import com.opspilot.order.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;

    public OrderService(OrderRepository orderRepository) {   // constructor injection: Spring passes the repository in
        this.orderRepository = orderRepository;
    }

    @Transactional   // the order and all its items are saved together, or not at all
    public CreateOrderResponse createOrder(CreateOrderRequest request) {
        Order order = new Order(request.customerId(), null);
        request.items().forEach(item -> order.addItem(item.sku(), item.qty()));
        orderRepository.save(order);

        log.info("Order {} created for customer {} with {} item(s)",
                order.getId(), order.getCustomerId(), order.getItems().size());
        return new CreateOrderResponse(order.getId(), order.getStatus());
    }

    @Transactional(readOnly = true)   // read-only: no dirty checking, and the items can be read while mapping
    public OrderResponse getOrder(UUID id) {
        return orderRepository.findWithItemsById(id)
                .map(OrderResponse::from)
                .orElseThrow(() -> new OrderNotFoundException(id));
    }
}
