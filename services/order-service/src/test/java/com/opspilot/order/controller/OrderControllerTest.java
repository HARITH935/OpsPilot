package com.opspilot.order.controller;

import com.opspilot.order.dto.OrderResponse;
import com.opspilot.order.dto.OrderSummaryResponse;
import com.opspilot.order.dto.PageResponse;
import com.opspilot.order.exception.IdempotencyKeyReusedException;
import com.opspilot.order.exception.OrderNotFoundException;
import com.opspilot.order.model.OrderStatus;
import com.opspilot.order.service.CreateOrderResult;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
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

    private static final String VALID_BODY = """
            {"customerId": "c-1", "items": [{"sku": "SKU-1", "qty": 2}]}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    @Test
    void createOrderReturns202WithLocationAndOrderId() throws Exception {
        UUID id = UUID.randomUUID();
        when(orderService.createOrder(any(), any())).thenReturn(new CreateOrderResult(id, OrderStatus.PENDING, false));

        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/orders/" + id))
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(jsonPath("$.orderId").value(id.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void replayedOrderIsMarkedWithAHeader() throws Exception {
        UUID id = UUID.randomUUID();
        when(orderService.createOrder(any(), eq("key-1"))).thenReturn(new CreateOrderResult(id, OrderStatus.PENDING, true));

        mockMvc.perform(post("/api/orders").header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.orderId").value(id.toString()));
    }

    @Test
    void tooLongIdempotencyKeyIsRejected() throws Exception {
        mockMvc.perform(post("/api/orders").header("Idempotency-Key", "x".repeat(101))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors['Idempotency-Key']").value("size must be between 1 and 100"));

        verifyNoInteractions(orderService);
    }

    @Test
    void reusedKeyWithADifferentBodyReturns422Problem() throws Exception {
        when(orderService.createOrder(any(), eq("key-1"))).thenThrow(new IdempotencyKeyReusedException("key-1"));

        mockMvc.perform(post("/api/orders").header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Idempotency-Key reused"));
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

    @Test
    void listOrdersReturnsAPage() throws Exception {
        UUID id = UUID.randomUUID();
        when(orderService.listOrders(OrderStatus.PENDING, 1, 5)).thenReturn(new PageResponse<>(
                List.of(new OrderSummaryResponse(id, "c-1", OrderStatus.PENDING, null, Instant.parse("2026-10-30T10:00:00Z"))),
                1, 5, 6, 2));

        mockMvc.perform(get("/api/orders").param("status", "PENDING").param("page", "1").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(id.toString()))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.totalElements").value(6))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void listOrdersUsesSensibleDefaults() throws Exception {
        when(orderService.listOrders(null, 0, 20)).thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isOk());

        verify(orderService).listOrders(null, 0, 20);   // no status filter, first page, 20 per page
    }

    @Test
    void listOrdersRejectsATooLargePageSize() throws Exception {
        mockMvc.perform(get("/api/orders").param("size", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors.size").value("must be less than or equal to 100"));

        verifyNoInteractions(orderService);
    }

    @Test
    void listOrdersRejectsAnUnknownStatus() throws Exception {
        mockMvc.perform(get("/api/orders").param("status", "SHIPPED"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }
}
