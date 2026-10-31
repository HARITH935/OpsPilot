package com.opspilot.order.controller;

import com.opspilot.order.dto.CreateOrderRequest;
import com.opspilot.order.dto.CreateOrderResponse;
import com.opspilot.order.dto.OrderResponse;
import com.opspilot.order.dto.OrderSummaryResponse;
import com.opspilot.order.dto.PageResponse;
import com.opspilot.order.model.OrderStatus;
import com.opspilot.order.service.CreateOrderResult;
import com.opspilot.order.service.OrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    public ResponseEntity<CreateOrderResponse> createOrder(
            @RequestHeader(name = "Idempotency-Key", required = false) @Size(min = 1, max = 100) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request) {
        CreateOrderResult result = orderService.createOrder(request, idempotencyKey);

        // Relative URL: stays correct behind the gateway / ingress, whatever the public host name is
        URI location = URI.create("/api/orders/" + result.orderId());
        ResponseEntity.BodyBuilder response = ResponseEntity.accepted().location(location);
        if (result.replayed()) {
            response.header("Idempotent-Replayed", "true");   // tells the client: nothing new was created
        }
        return response.body(new CreateOrderResponse(result.orderId(), result.status()));
    }

    @GetMapping("/{id}")   // a non-UUID id is rejected with 400 before this method runs
    public OrderResponse getOrder(@PathVariable UUID id) {
        return orderService.getOrder(id);
    }

    // GET /api/orders?status=PENDING&page=0&size=20 — every parameter is optional
    @GetMapping
    public PageResponse<OrderSummaryResponse> listOrders(
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {   // cap the page size: no "give me everything"
        return orderService.listOrders(status, page, size);
    }
}
