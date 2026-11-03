package com.opspilot.gateway;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.opspilot.gateway.config.JwtProperties;
import com.opspilot.gateway.service.TokenService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Login and access rules, end to end: the whole gateway with the demo users switched on, in front of fake services.
// The password is a test value, not a secret: the real one is only in deploy/docker/.env (never in Git).
// The tests log in dozens of times from one address: the login rate limit (RateLimitIntegrationTest) is raised here.
@SpringBootTest(properties = {"gateway.demo-users.enabled=true",
        "gateway.demo-users.password=" + AuthIntegrationTest.PASSWORD, "gateway.rate-limit.login.requests=1000"})
@AutoConfigureMockMvc
@Import(RedisTestcontainersConfiguration.class)   // the rate limiter needs a Redis
class AuthIntegrationTest {

    static final String PASSWORD = "test-password-for-demo-users";
    private static final String NEW_PRODUCT = "{\"sku\":\"SKU-T1\",\"name\":\"Test\",\"price\":9.99,\"stock\":5}";

    private static final FakeService orders = FakeService.start();
    private static final FakeService products = FakeService.start();

    @DynamicPropertySource
    static void serviceUrls(DynamicPropertyRegistry registry) {
        registry.add("ORDER_SERVICE_URL", orders::url);
        registry.add("INVENTORY_SERVICE_URL", products::url);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtDecoder jwtDecoder;   // the gateway's own checker

    @Autowired
    private RSAKey signingKey;

    @Autowired
    private JwtProperties jwtProperties;

    @BeforeEach
    void reset() {
        orders.reset();
        products.reset();
    }

    @AfterAll
    static void stop() {
        orders.close();
        products.close();
    }

    @Test
    void loginGivesASignedTokenForThatUser() throws Exception {
        String body = login("admin", PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.expires_in").value(1800))   // 30 minutes
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))   // never cached
                .andReturn().getResponse().getContentAsString();

        Jwt jwt = jwtDecoder.decode(JsonPath.read(body, "$.access_token"));
        assertThat(jwt.getSubject()).isEqualTo("demo:admin");
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("ADMIN");
    }

    @Test
    void aWrongPasswordAndAnUnknownUserGetTheSameAnswer() throws Exception {
        String wrongPassword = login("admin", "not-the-password").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Login failed"))
                .andReturn().getResponse().getContentAsString();
        String unknownUser = login("root", PASSWORD).andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(unknownUser).isEqualTo(wrongPassword);   // nothing tells which names exist
    }

    @Test
    void aLoginWithoutAPasswordIsABadRequest() throws Exception {
        mockMvc.perform(post("/auth/token").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"admin\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void thePublishedPublicKeyChecksOurTokens() throws Exception {
        String token = tokenFor("viewer");
        String jwks = mockMvc.perform(get("/.well-known/jwks.json")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // What another service (the agent, later) does: read the key set, check a token with it
        RSAKey published = JWKSet.parse(jwks).getKeys().getFirst().toRSAKey();
        assertThat(published.isPrivate()).isFalse();   // the private key is never published
        assertThat(published.getKeyID()).isEqualTo(signingKey.getKeyID());
        assertThat(NimbusJwtDecoder.withPublicKey(published.toRSAPublicKey()).build().decode(token).getSubject())
                .isEqualTo("demo:viewer");
    }

    @Test
    void theAdvertisedDescriptionOfThisApiIsTrue() throws Exception {
        // The 401's WWW-Authenticate header points clients here (RFC 9728)
        mockMvc.perform(get("/.well-known/oauth-protected-resource"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bearer_methods_supported[0]").value("header"))
                .andExpect(jsonPath("$.tls_client_certificate_bound_access_tokens").value(false));
    }

    @Test
    void changingTheCatalogueWithoutATokenIsRefusedWith401() throws Exception {
        newProduct(null)
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Login required"));

        assertThat(products.received()).isEmpty();   // refused at the door: inventory never saw it
    }

    @Test
    void viewerAndEngineerMayNotChangeTheCatalogue() throws Exception {
        for (String user : List.of("viewer", "engineer")) {
            newProduct(tokenFor(user))
                    .andExpect(status().isForbidden())
                    .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("insufficient_scope")))
                    .andExpect(jsonPath("$.title").value("Not allowed"));
        }
        assertThat(products.received()).isEmpty();
    }

    @Test
    void adminMayAndTheRequestReachesInventoryUnchanged() throws Exception {
        products.answer(201, NEW_PRODUCT, Map.of("Location", "/api/products/SKU-T1"));
        String token = tokenFor("admin");

        newProduct(token)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/products/SKU-T1"));

        FakeService.Received sent = products.lastReceived();
        assertThat(sent.method()).isEqualTo("POST");
        assertThat(sent.body()).isEqualTo(NEW_PRODUCT);
        assertThat(sent.header("Authorization")).isEqualTo("Bearer " + token);   // passed on, for later use
    }

    @Test
    void readingAndOrderingStayOpenWithoutAToken() throws Exception {
        orders.answer(202, "{\"orderId\":\"o-1\"}", Map.of());

        mockMvc.perform(get("/api/products/SKU-1")).andExpect(status().isOk());
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isAccepted());
    }

    @Test
    void aBrokenTokenIsRefusedEvenOnAnOpenPath() throws Exception {
        mockMvc.perform(get("/api/products/SKU-1").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("invalid_token")))
                .andExpect(jsonPath("$.title").value("Invalid token"));

        assertThat(products.received()).isEmpty();
    }

    @Test
    void anExpiredTokenIsRefused() throws Exception {
        // Made 40 minutes ago with a 30-minute lifetime: expired 10 minutes ago (well past the 60 s clock skew)
        Clock fortyMinutesAgo = Clock.fixed(Instant.now().minus(Duration.ofMinutes(40)), ZoneOffset.UTC);
        String expired = new TokenService(encoderFor(signingKey), signingKey, jwtProperties, fortyMinutesAgo)
                .issue("demo:admin", List.of("ADMIN")).value();

        newProduct(expired).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.title").value("Invalid token"));
        assertThat(products.received()).isEmpty();
    }

    @Test
    void aTokenSignedWithSomeoneElsesKeyIsRefused() throws Exception {
        RSAKey otherKey = newKey();   // looks exactly like ours, claims ADMIN, but the gateway didn't sign it
        String forged = new TokenService(encoderFor(otherKey), otherKey, jwtProperties, Clock.systemUTC())
                .issue("demo:admin", List.of("ADMIN")).value();

        newProduct(forged).andExpect(status().isUnauthorized());
        assertThat(products.received()).isEmpty();
    }

    @Test
    void aTokenFromAnotherIssuerIsRefused() throws Exception {
        JwtProperties otherIssuer = new JwtProperties("someone-else", Duration.ofMinutes(30), null, null);
        String token = new TokenService(encoderFor(signingKey), signingKey, otherIssuer, Clock.systemUTC())
                .issue("demo:admin", List.of("ADMIN")).value();

        newProduct(token).andExpect(status().isUnauthorized());
        assertThat(products.received()).isEmpty();
    }

    private ResultActions login(String username, String password) throws Exception {
        return mockMvc.perform(post("/auth/token").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"));
    }

    private String tokenFor(String username) throws Exception {
        String body = login(username, PASSWORD).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.access_token");
    }

    private ResultActions newProduct(String token) throws Exception {
        var request = post("/api/products").contentType(MediaType.APPLICATION_JSON).content(NEW_PRODUCT);
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return mockMvc.perform(request);
    }

    private static NimbusJwtEncoder encoderFor(RSAKey key) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
    }

    private static RSAKey newKey() {
        try {
            return new RSAKeyGenerator(2048).keyIDFromThumbprint(true).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
