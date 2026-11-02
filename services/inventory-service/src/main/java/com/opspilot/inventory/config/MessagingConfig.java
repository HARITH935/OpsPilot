package com.opspilot.inventory.config;

import com.opspilot.messaging.MessagingConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

// A separate class, not annotations on InventoryServiceApplication: test slices like @WebMvcTest skip @Configuration
// classes, so controller tests don't need Kafka or the outbox
@Configuration(proxyBeanMethods = false)
@EnableScheduling                       // runs @Scheduled methods, such as the outbox publisher (every 0.5 s)
@Import(MessagingConfiguration.class)   // outbox, duplicate check and Kafka settings from common-messaging
class MessagingConfig {
}
