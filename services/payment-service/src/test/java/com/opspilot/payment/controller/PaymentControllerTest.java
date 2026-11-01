package com.opspilot.payment.controller;

import com.opspilot.payment.dto.PageResponse;
import com.opspilot.payment.dto.PaymentResponse;
import com.opspilot.payment.exception.PaymentNotFoundException;
import com.opspilot.payment.model.PaymentStatus;
import com.opspilot.payment.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Loads only the web layer (this controller + the error handler). The service is a Mockito mock: no database.
@WebMvcTest(PaymentController.class)
class PaymentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentService paymentService;

    @Test
    void listPaymentsReturnsAPage() throws Exception {
        PaymentResponse failed = failedPayment(UUID.randomUUID());
        when(paymentService.listPayments(PaymentStatus.FAILED, 1, 5))
                .thenReturn(new PageResponse<>(List.of(failed), 1, 5, 6, 2));

        mockMvc.perform(get("/api/payments").param("status", "FAILED").param("page", "1").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(failed.id().toString()))
                .andExpect(jsonPath("$.content[0].failureReason").value("PROVIDER_ERROR"))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.totalElements").value(6))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void listPaymentsUsesSensibleDefaults() throws Exception {
        when(paymentService.listPayments(null, 0, 20)).thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        mockMvc.perform(get("/api/payments"))
                .andExpect(status().isOk());

        verify(paymentService).listPayments(null, 0, 20);   // no status filter, first page, 20 per page
    }

    @Test
    void listPaymentsRejectsATooLargePageSize() throws Exception {
        mockMvc.perform(get("/api/payments").param("size", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors.size").value("must be less than or equal to 100"));

        verifyNoInteractions(paymentService);
    }

    @Test
    void listPaymentsRejectsAnUnknownStatus() throws Exception {
        mockMvc.perform(get("/api/payments").param("status", "REFUNDED"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void getPaymentReturnsThePayment() throws Exception {
        UUID id = UUID.randomUUID();
        PaymentResponse failed = failedPayment(id);
        when(paymentService.getPayment(id)).thenReturn(failed);

        mockMvc.perform(get("/api/payments/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.orderId").value(failed.orderId().toString()))
                .andExpect(jsonPath("$.amount").value(209.97))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("PROVIDER_ERROR"));
    }

    @Test
    void unknownPaymentReturns404Problem() throws Exception {
        UUID id = UUID.randomUUID();
        when(paymentService.getPayment(id)).thenThrow(new PaymentNotFoundException(id));

        mockMvc.perform(get("/api/payments/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Payment not found"))
                .andExpect(jsonPath("$.detail").value("Payment " + id + " does not exist"));
    }

    @Test
    void nonUuidPaymentIdReturns400Problem() throws Exception {
        mockMvc.perform(get("/api/payments/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void paymentsCannotBeCreatedOverHttp() throws Exception {
        mockMvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());   // 405: only the saga creates payments

        verifyNoInteractions(paymentService);
    }

    private static PaymentResponse failedPayment(UUID id) {
        return new PaymentResponse(id, UUID.randomUUID(), new BigDecimal("209.97"), PaymentStatus.FAILED,
                "PROVIDER_ERROR", Instant.parse("2026-11-01T10:00:00Z"));
    }
}
