package com.opspilot.order.controller;

import com.opspilot.order.dto.CreateOrderResponse;
import com.opspilot.order.dto.OrderResponse;
import com.opspilot.order.exception.OrderNotFoundException;
import com.opspilot.order.model.OrderStatus;
import com.opspilot.order.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Loads only the web layer (this controller + the error handler). The service is a Mockito mock: no database.
@WebMvcTest(OrderController.class)
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    @Test
    void createOrderReturns202WithLocationAndOrderId() throws Exception {
        UUID id = UUID.randomUUID();
        when(orderService.createOrder(any())).thenReturn(new CreateOrderResponse(id, OrderStatus.PENDING));

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId": "c-1", "items": [{"sku": "SKU-1", "qty": 2}]}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/orders/" + id))
                .andExpect(jsonPath("$.orderId").value(id.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void invalidFieldsReturn400ProblemNamingEachField() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId": "", "items": [{"sku": "SKU-1", "qty": 0}, {"sku": "SKU-2"}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors.customerId").value("must not be blank"))
                .andExpect(jsonPath("$.errors['items[0].qty']").value("must be greater than 0"))
                .andExpect(jsonPath("$.errors['items[1].qty']").value("must not be null"));

        verifyNoInteractions(orderService);   // invalid input never reaches the business logic
    }

    @Test
    void orderWithoutItemsIsRejected() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId": "c-1", "items": []}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.items").value("must not be empty"));
    }

    @Test
    void malformedJsonReturns400Problem() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void getOrderReturnsOrderWithItems() throws Exception {
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-10-30T10:00:00Z");
        when(orderService.getOrder(id)).thenReturn(new OrderResponse(
                id, "c-1", OrderStatus.PENDING, null, null,
                List.of(new OrderResponse.Item("SKU-1", 2, null)), createdAt, createdAt));

        mockMvc.perform(get("/api/orders/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.items[0].sku").value("SKU-1"))
                .andExpect(jsonPath("$.items[0].qty").value(2));
    }

    @Test
    void unknownOrderReturns404Problem() throws Exception {
        UUID id = UUID.randomUUID();
        when(orderService.getOrder(id)).thenThrow(new OrderNotFoundException(id));

        mockMvc.perform(get("/api/orders/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Order not found"))
                .andExpect(jsonPath("$.detail").value("Order " + id + " does not exist"));
    }

    @Test
    void nonUuidOrderIdReturns400Problem() throws Exception {
        mockMvc.perform(get("/api/orders/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }
}
