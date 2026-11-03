package com.opspilot.gateway.config;

import org.springframework.boot.http.client.JdkClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.autoconfigure.ClientHttpRequestFactoryBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;

// The HTTP client the gateway uses to call the services: Java's built-in java.net.http.HttpClient, with the timeouts
// from application.yml (spring.http.clients.*)
@Configuration(proxyBeanMethods = false)
class HttpClientConfig {

    // Plain HTTP/1.1. By default the JDK client asks every server to switch to HTTP/2 ("Upgrade: h2c") on each request;
    // the services only speak HTTP/1.1, so that is wasted work and extra headers on every forwarded request.
    @Bean
    ClientHttpRequestFactoryBuilderCustomizer<JdkClientHttpRequestFactoryBuilder> http11Only() {
        return builder -> builder.withHttpClientCustomizer(client -> client.version(HttpClient.Version.HTTP_1_1));
    }
}
