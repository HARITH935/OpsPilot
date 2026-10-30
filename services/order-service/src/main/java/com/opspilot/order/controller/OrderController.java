package com.opspilot.order.controller;

import com.opspilot.order.dto.CreateOrderRequest;
import com.opspilot.order.dto.CreateOrderResponse;
import com.opspilot.order.dto.OrderResponse;
import com.opspilot.order.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    // 202 Accepted (not 201 Created): the order is stored, but stock and payment are still being processed
    @PostMapping
    public ResponseEntity<CreateOrderResponse> createOrder(@Valid @RequestBody CreateOrderRequest request) {
        CreateOrderResponse response = orderService.createOrder(request);
        // Relative URL: stays correct behind the gateway / ingress, whatever the public host name is
        URI location = URI.create("/api/orders/" + response.orderId());
        return ResponseEntity.accepted().location(location).body(response);
    }

    @GetMapping("/{id}")   // a non-UUID id is rejected with 400 before this method runs
    public OrderResponse getOrder(@PathVariable UUID id) {
        return orderService.getOrder(id);
    }
}
