package com.opspilot.gateway.service;

import com.nimbusds.jose.jwk.RSAKey;
import com.opspilot.gateway.config.JwtProperties;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Makes signed tokens (JWT, RS256). A token is three base64 parts joined by dots: header.claims.signature.
 * The header names the algorithm and the key id; the claims say who it is for and until when it is valid; the
 * signature, made with the private key, proves the gateway wrote exactly these claims.
 */
@Service
public class TokenService {

    public static final String ROLES_CLAIM = "roles";   // e.g. ["ADMIN"]; SecurityConfig turns it into ROLE_ADMIN

    private final JwtEncoder encoder;
    private final RSAKey signingKey;
    private final JwtProperties properties;
    private final Clock clock;

    public TokenService(JwtEncoder encoder, RSAKey signingKey, JwtProperties properties, Clock clock) {
        this.encoder = encoder;
        this.signingKey = signingKey;
        this.properties = properties;
        this.clock = clock;
    }

    public IssuedToken issue(String subject, List<String> roles) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);   // JWT times are whole seconds
        Instant expiresAt = now.plus(properties.ttl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())   // iss: who made it
                .subject(subject)              // sub: who it is for, e.g. demo:admin
                .issuedAt(now)                 // iat
                .expiresAt(expiresAt)          // exp: refused after this
                .claim(ROLES_CLAIM, roles)
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(signingKey.getKeyID()).build();
        return new IssuedToken(encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue(), expiresAt);
    }

    public record IssuedToken(String value, Instant expiresAt) {
    }
}
