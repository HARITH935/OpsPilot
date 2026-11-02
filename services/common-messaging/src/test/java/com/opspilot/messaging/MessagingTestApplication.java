package com.opspilot.messaging;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;

// The smallest Spring Boot app that uses this library, for its tests (@SpringBootTest finds it by package).
// No @EnableScheduling on purpose: the tests call the publisher themselves, so nothing runs in the background.
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(MessagingConfiguration.class)
class MessagingTestApplication {
}
