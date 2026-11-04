package com.opspilot.payment.config;

import com.opspilot.payment.service.PaymentProvider;
import com.opspilot.payment.service.ResilientPaymentProvider;
import com.opspilot.payment.service.SimulatedPaymentProvider;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// The payment provider as PaymentService gets it: the simulated one, wrapped in ResilientPaymentProvider
@Configuration(proxyBeanMethods = false)
class ProviderResilienceConfig {

    // Each provider call runs on a virtual thread of its own (see ResilientPaymentProvider): cheap, one per call
    @Bean(destroyMethod = "close")
    ExecutorService providerCalls() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    // Two beans now implement PaymentProvider: @Primary makes this one the one handed out
    @Bean
    @Primary
    PaymentProvider resilientPaymentProvider(SimulatedPaymentProvider simulated, ProviderResilienceProperties properties,
                                             @Qualifier("providerCalls") ExecutorService calls, MeterRegistry meters) {
        return new ResilientPaymentProvider(simulated, properties, calls, meters);
    }
}
