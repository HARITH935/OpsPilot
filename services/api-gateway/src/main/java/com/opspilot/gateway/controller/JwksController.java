package com.opspilot.gateway.controller;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

// The public half of the signing key, as a JWK Set (RFC 7517): {"keys":[{"kty":"RSA","kid":"...","n":"...","e":"AQAB"}]}.
// Anyone may read it: with it, another service (the agent, in Phase 9) can check our tokens without asking us.
// It can only check signatures, never make them: the private key is not in it.
@RestController
class JwksController {

    private final Map<String, Object> publicKeys;

    JwksController(RSAKey signingKey) {
        this.publicKeys = new JWKSet(signingKey).toPublicJWKSet().toJSONObject();   // toPublic...: drops the private parts
    }

    @GetMapping("/.well-known/jwks.json")   // ".well-known": the standard place where clients look for such files
    Map<String, Object> jwks() {
        return publicKeys;
    }
}
