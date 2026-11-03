package com.opspilot.gateway.service;

import com.opspilot.gateway.config.DemoUsersProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The three demo users of the architecture doc (B2.4), one per role. Local development and evals only: this bean
 * exists only with DEMO_USERS_ENABLED=true. Real people log in with GitHub instead (Phase 12).
 */
@Service
@ConditionalOnBooleanProperty("gateway.demo-users.enabled")
public class DemoUsers {

    private static final Map<String, List<String>> ROLES = Map.of(
            "viewer", List.of("VIEWER"),
            "engineer", List.of("ENGINEER"),
            "admin", List.of("ADMIN"));

    private final byte[] password;

    public DemoUsers(DemoUsersProperties properties) {
        this.password = properties.password().getBytes(StandardCharsets.UTF_8);
    }

    // The user's roles if the name and password are right; empty for a wrong password AND for an unknown user, so a
    // caller can't find out which names exist
    public Optional<List<String>> authenticate(String username, String password) {
        // MessageDigest.isEqual compares in constant time: it doesn't stop at the first wrong character, so the time
        // an answer takes gives away nothing about how much of a guessed password was right
        boolean passwordMatches = MessageDigest.isEqual(this.password, password.getBytes(StandardCharsets.UTF_8));
        List<String> roles = ROLES.get(username);
        return passwordMatches && roles != null ? Optional.of(roles) : Optional.empty();
    }
}
