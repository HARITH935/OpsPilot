package com.opspilot.payment;

import com.opspilot.payment.dto.PaymentResponse;
import com.opspilot.payment.exception.PaymentProviderException;
import com.opspilot.payment.repository.PaymentRepository;
import com.opspilot.payment.service.PaymentProvider;
import com.opspilot.payment.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// End to end inside one JVM: charge → real Postgres (Testcontainers) → read back over HTTP (MockMvc).
// The provider is a mock here, so each test decides whether it fails; SimulatedPaymentProviderTest covers the real one.
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PaymentApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRepository paymentRepository;

    @MockitoBean
    private PaymentProvider paymentProvider;

    @BeforeEach
    void emptyTheDatabase() {
        paymentRepository.deleteAllInBatch();
    }

    @Test
    void chargedPaymentIsStoredAndCanBeReadOverHttp() throws Exception {
        UUID orderId = UUID.randomUUID();
        PaymentResponse payment = paymentService.charge(orderId, new BigDecimal("209.97"));

        mockMvc.perform(get("/api/payments/{id}", payment.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.amount").value(209.97))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.failureReason").value(nullValue()));
    }

    @Test
    void failedChargeIsStoredWithItsReason() throws Exception {
        doThrow(new PaymentProviderException("provider down")).when(paymentProvider).charge(any(), any());

        paymentService.charge(UUID.randomUUID(), new BigDecimal("10.00"));

        mockMvc.perform(get("/api/payments").param("status", "FAILED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].failureReason").value("PROVIDER_ERROR"));
    }

    @Test
    void chargingTheSameOrderTwiceCallsTheProviderOnce() {
        UUID orderId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("10.00");

        PaymentResponse first = paymentService.charge(orderId, amount);
        PaymentResponse second = paymentService.charge(orderId, amount);   // e.g. the same event delivered twice

        assertThat(second.id()).isEqualTo(first.id());
        verify(paymentProvider, times(1)).charge(orderId, amount);
        assertThat(paymentRepository.count()).isEqualTo(1);
    }

    @Test
    void listShowsNewestFirst() throws Exception {
        paymentService.charge(UUID.randomUUID(), new BigDecimal("10.00"));
        paymentService.charge(UUID.randomUUID(), new BigDecimal("20.00"));
        PaymentResponse newest = paymentService.charge(UUID.randomUUID(), new BigDecimal("30.00"));

        mockMvc.perform(get("/api/payments").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(newest.id().toString()))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));
    }
}
