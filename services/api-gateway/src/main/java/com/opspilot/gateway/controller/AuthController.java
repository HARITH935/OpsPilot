package com.opspilot.gateway.controller;

import com.opspilot.gateway.config.JwtProperties;
import com.opspilot.gateway.dto.TokenRequest;
import com.opspilot.gateway.dto.TokenResponse;
import com.opspilot.gateway.exception.InvalidCredentialsException;
import com.opspilot.gateway.service.DemoUsers;
import com.opspilot.gateway.service.TokenService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// Login for the demo users. Only exists with DEMO_USERS_ENABLED=true; otherwise POST /auth/token is a plain 404.
@RestController
@ConditionalOnBooleanProperty("gateway.demo-users.enabled")
class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final DemoUsers demoUsers;
    private final TokenService tokens;
    private final JwtProperties properties;

    AuthController(DemoUsers demoUsers, TokenService tokens, JwtProperties properties) {
        this.demoUsers = demoUsers;
        this.tokens = tokens;
        this.properties = properties;
    }

    @PostMapping("/auth/token")
    TokenResponse token(@Valid @RequestBody TokenRequest request) {
        List<String> roles = demoUsers.authenticate(request.username(), request.password())
                .orElseThrow(() -> {
                    log.info("Login refused for user '{}'", request.username());
                    return new InvalidCredentialsException();
                });
        TokenService.IssuedToken token = tokens.issue("demo:" + request.username(), roles);
        log.info("Token issued for demo:{} {}, valid until {}", request.username(), roles, token.expiresAt());
        return TokenResponse.bearer(token.value(), properties.ttl().toSeconds());
    }
}
