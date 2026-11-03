package com.opspilot.gateway.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

// The token settings, read from gateway.jwt.* in application.yml (env vars JWT_ISSUER, JWT_TTL, JWT_*_KEY_FILE).
// Checked at startup: a bad value stops the gateway instead of producing tokens nobody can use.
@ConfigurationProperties(prefix = "gateway.jwt")
@Validated
public record JwtProperties(
        @NotBlank String issuer,                                         // the "iss" claim: who made the token
        @NotNull @DurationMin(seconds = 1) @DurationMax(hours = 24) Duration ttl,   // how long a token stays valid
        String privateKeyFile,   // PEM files of the signing key pair; both empty = a temporary key (JwtConfig)
        String publicKeyFile
) {

    public JwtProperties {
        privateKeyFile = blankToNull(privateKeyFile);
        publicKeyFile = blankToNull(publicKeyFile);
        if ((privateKeyFile == null) != (publicKeyFile == null)) {
            throw new IllegalArgumentException(
                    "Set both JWT_PRIVATE_KEY_FILE and JWT_PUBLIC_KEY_FILE, or neither (for a temporary key)");
        }
    }

    public boolean hasKeyFiles() {
        return privateKeyFile != null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
