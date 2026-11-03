package com.opspilot.gateway.config;

import com.nimbusds.jose.jwk.RSAKey;
import com.opspilot.gateway.service.TokenService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// The signing key: loaded from PEM files (as a Kubernetes Secret will provide them), or made at startup. No Spring.
class JwtConfigTest {

    private final JwtConfig config = new JwtConfig();

    @TempDir
    private Path dir;   // JUnit makes an empty folder for each test and deletes it afterwards

    @Test
    void theKeyPairIsLoadedFromPemFiles() throws Exception {
        KeyPair pair = newKeyPair();
        JwtProperties properties = withKeyFiles(writePem("PRIVATE KEY", pair.getPrivate().getEncoded()),
                writePem("PUBLIC KEY", pair.getPublic().getEncoded()));

        RSAKey key = config.signingKey(properties);

        assertThat(key.toRSAPublicKey()).isEqualTo(pair.getPublic());
        assertThat(key.toRSAPrivateKey()).isEqualTo(pair.getPrivate());
        assertThat(key.getKeyID()).isEqualTo(key.computeThumbprint().toString());   // kid = hash of the public key
    }

    @Test
    void twoHalvesOfDifferentKeyPairsAreRefused() {
        JwtProperties properties = withKeyFiles(writePem("PRIVATE KEY", newKeyPair().getPrivate().getEncoded()),
                writePem("PUBLIC KEY", newKeyPair().getPublic().getEncoded()));

        assertThatThrownBy(() -> config.signingKey(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not one key pair");
    }

    @Test
    void aMissingKeyFileStopsTheStart() {
        JwtProperties properties = withKeyFiles(dir.resolve("missing.pem").toString(), dir.resolve("also-missing.pem").toString());

        assertThatThrownBy(() -> config.signingKey(properties))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("missing.pem");
    }

    @Test
    void withoutKeyFilesATemporaryKeyIsMade() throws Exception {
        RSAKey key = config.signingKey(new JwtProperties("opspilot-gateway", Duration.ofMinutes(30), null, null));

        assertThat(key.isPrivate()).isTrue();   // it can sign
        assertThat(key.size()).isEqualTo(2048);
        assertThat(key.getKeyID()).isNotBlank();
    }

    @Test
    void theDecoderAcceptsOurTokensAndRefusesTokensFromAnotherIssuer() throws Exception {
        JwtProperties ours = new JwtProperties("opspilot-gateway", Duration.ofMinutes(30), null, null);
        RSAKey key = config.signingKey(ours);
        JwtDecoder decoder = config.jwtDecoder(key, ours);
        JwtProperties someoneElse = new JwtProperties("someone-else", Duration.ofMinutes(30), null, null);

        String ourToken = new TokenService(config.jwtEncoder(key), key, ours, Clock.systemUTC())
                .issue("demo:admin", List.of("ADMIN")).value();
        String theirToken = new TokenService(config.jwtEncoder(key), key, someoneElse, Clock.systemUTC())
                .issue("demo:admin", List.of("ADMIN")).value();

        assertThat(decoder.decode(ourToken).getSubject()).isEqualTo("demo:admin");
        assertThatThrownBy(() -> decoder.decode(theirToken))   // even with our own key: wrong "iss"
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("iss");
    }

    private JwtProperties withKeyFiles(String privateKeyFile, String publicKeyFile) {
        return new JwtProperties("opspilot-gateway", Duration.ofMinutes(30), privateKeyFile, publicKeyFile);
    }

    // The same format openssl writes: a header line, the key in base64 (64 characters per line), a footer line
    private String writePem(String type, byte[] der) {
        String pem = "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
                + "\n-----END " + type + "-----\n";
        try {
            return Files.writeString(Files.createTempFile(dir, "jwt-", ".pem"), pem).toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static KeyPair newKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
