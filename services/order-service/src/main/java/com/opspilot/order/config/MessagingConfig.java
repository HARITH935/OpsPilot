package com.opspilot.order.config;

import com.opspilot.messaging.MessagingConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

// A separate class, not annotations on OrderServiceApplication: test slices like @WebMvcTest skip @Configuration
// classes, so controller tests don't need Kafka or the outbox
@Configuration(proxyBeanMethods = false)
@EnableScheduling                       // runs @Scheduled methods: the outbox publisher (every 0.5 s), SagaTimeoutJob
@Import(MessagingConfiguration.class)   // outbox writer + publisher from common-messaging
class MessagingConfig {
}
