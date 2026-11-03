package com.opspilot.gateway.service;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.opspilot.gateway.config.JwtProperties;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Plain unit test: a real key and the real Nimbus encoder, but no Spring. The clock is fixed, so the times are exact.
class TokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-03T10:00:00Z");

    private final RSAKey key = newKey();
    private final TokenService tokens = new TokenService(new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key))),
            key, new JwtProperties("opspilot-gateway", Duration.ofMinutes(30), null, null), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void aTokenSaysWhoItIsForWhichRolesAndUntilWhen() {
        TokenService.IssuedToken token = tokens.issue("demo:admin", List.of("ADMIN"));

        Jwt jwt = signatureOnly(key).decode(token.value());   // throws unless our key signed it
        assertThat(jwt.getHeaders()).containsEntry("alg", "RS256").containsEntry("kid", key.getKeyID());
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("opspilot-gateway");
        assertThat(jwt.getSubject()).isEqualTo("demo:admin");
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("ADMIN");
        assertThat(jwt.getIssuedAt()).isEqualTo(NOW);
        assertThat(jwt.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(30))).isEqualTo(token.expiresAt());
    }

    @Test
    void changingAnythingInTheTokenBreaksItsSignature() {
        String[] parts = tokens.issue("demo:viewer", List.of("VIEWER")).value().split("\\.");   // header.claims.signature
        // Anyone can read the claims (they are only base64), so anyone could try to promote themselves...
        String claims = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8).replace("VIEWER", "ADMIN");
        String forged = parts[0] + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(claims.getBytes(StandardCharsets.UTF_8)) + "." + parts[2];

        assertThatThrownBy(() -> signatureOnly(key).decode(forged))   // ...but the old signature no longer fits
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("Invalid signature");
    }

    @Test
    void aTokenIsCheckedOnlyWithThePublicKeyOfTheKeyThatSignedIt() {
        String token = tokens.issue("demo:admin", List.of("ADMIN")).value();

        assertThatThrownBy(() -> signatureOnly(newKey()).decode(token)).isInstanceOf(JwtException.class);
    }

    // A decoder that checks only the signature, not the times: the fixed clock above lies in the past
    private static JwtDecoder signatureOnly(RSAKey key) {
        try {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
            decoder.setJwtValidator(jwt -> OAuth2TokenValidatorResult.success());
            return decoder;
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static RSAKey newKey() {
        try {
            return new RSAKeyGenerator(2048).keyIDFromThumbprint(true).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
