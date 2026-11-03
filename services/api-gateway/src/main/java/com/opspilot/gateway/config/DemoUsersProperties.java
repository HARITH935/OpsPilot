package com.opspilot.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// The demo users (viewer / engineer / admin), read from gateway.demo-users.* (env vars DEMO_USERS_ENABLED and
// DEMO_USERS_PASSWORD). For local development and the evals only: off unless switched on.
@ConfigurationProperties(prefix = "gateway.demo-users")
public record DemoUsersProperties(
        boolean enabled,    // false: POST /auth/token doesn't exist at all (404)
        String password     // shared by the three demo users; never in Git (deploy/docker/.env locally)
) {

    static final int MIN_PASSWORD_LENGTH = 12;

    // Switched on by mistake without a real password, anyone could get an ADMIN token: refuse to start instead
    public DemoUsersProperties {
        if (enabled && (password == null || password.length() < MIN_PASSWORD_LENGTH)) {
            throw new IllegalArgumentException("DEMO_USERS_ENABLED=true needs DEMO_USERS_PASSWORD, at least "
                    + MIN_PASSWORD_LENGTH + " characters");
        }
    }
}
