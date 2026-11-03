package com.opspilot.gateway.service;

import com.opspilot.gateway.config.RateLimitProperties;
import com.opspilot.gateway.config.RateLimitProperties.Limit;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Plain unit test: whose IP is it? MockHttpServletRequest stands in for a real request.
class ClientIpResolverTest {

    @Test
    void withoutTrustedProxiesTheConnectionAddressCountsAndTheHeaderIsIgnored() {
        ClientIpResolver resolver = resolverTrusting();

        assertThat(resolver.clientIp(request("198.51.100.20", null))).isEqualTo("198.51.100.20");
        // A client writing any address it likes into the header gets nothing for it
        assertThat(resolver.clientIp(request("198.51.100.20", "1.2.3.4"))).isEqualTo("198.51.100.20");
    }

    @Test
    void behindATrustedProxyTheAddressItAddedCounts() {
        ClientIpResolver resolver = resolverTrusting("10.0.0.0/8");   // e.g. the Ingress pods

        assertThat(resolver.clientIp(request("10.1.2.3", "203.0.113.7"))).isEqualTo("203.0.113.7");
    }

    @Test
    void addressesTheClientWroteItselfAreSkipped() {
        ClientIpResolver resolver = resolverTrusting("10.0.0.0/8");

        // The client sent "X-Forwarded-For: 1.2.3.4"; the Ingress added the address it really saw
        assertThat(resolver.clientIp(request("10.1.2.3", "1.2.3.4, 203.0.113.7"))).isEqualTo("203.0.113.7");
    }

    @Test
    void severalTrustedProxiesInARowAreAllSkipped() {
        ClientIpResolver resolver = resolverTrusting("10.0.0.0/8", "192.168.1.1");

        assertThat(resolver.clientIp(request("192.168.1.1", "203.0.113.7, 10.9.9.9"))).isEqualTo("203.0.113.7");
    }

    @Test
    void somethingThatIsNotAnIpAddressIsNeverTakenForAProxy() {
        ClientIpResolver resolver = resolverTrusting("127.0.0.1");

        // "localhost" is not looked up in DNS (it would become 127.0.0.1, a trusted proxy): it stays a stranger
        assertThat(resolver.clientIp(request("127.0.0.1", "203.0.113.7, localhost"))).isEqualTo("localhost");
        assertThat(resolver.clientIp(request("127.0.0.1", "unknown"))).isEqualTo("unknown");
    }

    @Test
    void aWronglyWrittenProxyStopsTheStart() {
        assertThatThrownBy(() -> resolverTrusting("10.0.0.0/99")).isInstanceOf(IllegalArgumentException.class);
    }

    private static ClientIpResolver resolverTrusting(String... proxies) {
        Limit limit = new Limit(10, Duration.ofSeconds(1));
        return new ClientIpResolver(new RateLimitProperties(true, limit, limit, limit, List.of(proxies)));
    }

    private static MockHttpServletRequest request(String remoteAddr, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders");
        request.setRemoteAddr(remoteAddr);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        return request;
    }
}
