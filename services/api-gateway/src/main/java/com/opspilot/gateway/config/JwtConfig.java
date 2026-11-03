package com.opspilot.gateway.config;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;

/**
 * The RSA key pair behind the tokens (RS256), and the two beans that use it: the encoder signs new tokens with the
 * private key, the decoder checks every incoming token with the public key. The private key never leaves the gateway;
 * the public key is published at /.well-known/jwks.json (JwksController) so other services can check tokens too.
 */
@Configuration(proxyBeanMethods = false)
class JwtConfig {

    private static final Logger log = LoggerFactory.getLogger(JwtConfig.class);

    // The key pair as a JWK (JSON Web Key): the RSA keys plus a key id ("kid"), the key's thumbprint (a hash of the
    // public key). Every token names its kid, so a checker knows which published key to use.
    @Bean
    RSAKey signingKey(JwtProperties properties) throws JOSEException {
        if (!properties.hasKeyFiles()) {
            log.warn("No JWT key files configured (JWT_PRIVATE_KEY_FILE / JWT_PUBLIC_KEY_FILE): using a temporary key "
                    + "made at startup. Tokens stop working when the gateway restarts. Fine locally, not in the cloud.");
            return new RSAKeyGenerator(2048).keyIDFromThumbprint(true).generate();
        }
        RSAPrivateKey privateKey = read(properties.privateKeyFile(), RsaKeyConverters.pkcs8());   // BEGIN PRIVATE KEY
        RSAPublicKey publicKey = read(properties.publicKeyFile(), RsaKeyConverters.x509());       // BEGIN PUBLIC KEY
        // Both halves of one RSA key pair share the same modulus (the big number n): otherwise every token would fail
        if (!privateKey.getModulus().equals(publicKey.getModulus())) {
            throw new IllegalStateException("JWT_PRIVATE_KEY_FILE and JWT_PUBLIC_KEY_FILE are not one key pair");
        }
        RSAKey key = new RSAKey.Builder(publicKey).privateKey(privateKey).keyIDFromThumbprint().build();
        log.info("JWT signing key {} loaded from {}", key.getKeyID(), properties.privateKeyFile());
        return key;
    }

    @Bean
    JwtEncoder jwtEncoder(RSAKey signingKey) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(signingKey)));
    }

    // Spring Security uses this bean to check the Bearer token of every request: the signature (RS256 with our public
    // key only), then the validators: not expired, not used before its time (60 s clock skew allowed), our issuer
    @Bean
    JwtDecoder jwtDecoder(RSAKey signingKey, JwtProperties properties) throws JOSEException {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(signingKey.toRSAPublicKey())
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }

    // "Now", as a bean: TokenService asks it for the time, and a test can use a clock that is set to the past
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    private static <K> K read(String file, Converter<InputStream, K> converter) {
        try (InputStream in = Files.newInputStream(Path.of(file))) {
            return converter.convert(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Can't read the JWT key file " + file, e);
        }
    }
}
