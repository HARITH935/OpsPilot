package com.opspilot.gateway.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

// Answer of POST /auth/token. The field names are the standard ones from OAuth 2.0 (RFC 6749, section 5.1), so any
// OAuth-aware client understands them: {"access_token":"eyJ...","token_type":"Bearer","expires_in":1800}
public record TokenResponse(
        @JsonProperty("access_token") String accessToken,   // send it back as "Authorization: Bearer <access_token>"
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_in") long expiresIn           // seconds until the token stops working
) {

    public static TokenResponse bearer(String token, long expiresIn) {
        return new TokenResponse(token, "Bearer", expiresIn);
    }
}
