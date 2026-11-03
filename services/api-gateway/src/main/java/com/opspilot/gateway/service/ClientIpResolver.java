package com.opspilot.gateway.service;

import com.opspilot.gateway.config.RateLimitProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The caller's IP address, for the anonymous rate limit. Normally that is simply the address the connection comes from.
 * Behind a proxy (the Ingress, Phase 4) the connection comes from the proxy, and the proxy writes the real client's
 * address at the end of the X-Forwarded-For header. But any client can send that header with any address in it, so it
 * is believed only as far as it was written by proxies we trust (GATEWAY_TRUSTED_PROXIES). Read from the right:
 * the first address that is not one of our proxies is the client.
 */
@Service
public class ClientIpResolver {

    private final List<IpAddressMatcher> trustedProxies;

    public ClientIpResolver(RateLimitProperties properties) {
        // Each entry is one address (10.0.0.5) or a range in CIDR form (10.0.0.0/8); a wrong one stops the start
        this.trustedProxies = properties.trustedProxies().stream().map(IpAddressMatcher::new).toList();
    }

    public String clientIp(HttpServletRequest request) {
        List<String> hops = new ArrayList<>();   // oldest first: client, proxy 1, proxy 2, ..., the connection itself
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null) {
            Arrays.stream(forwardedFor.split(",")).map(String::trim).filter(hop -> !hop.isEmpty()).forEach(hops::add);
        }
        hops.add(request.getRemoteAddr());

        for (int i = hops.size() - 1; i > 0; i--) {   // walk back from the connection, past our own proxies
            if (!isTrustedProxy(hops.get(i))) {
                return hops.get(i);
            }
        }
        return hops.getFirst();
    }

    private boolean isTrustedProxy(String address) {
        try {
            return trustedProxies.stream().anyMatch(proxy -> proxy.matches(address));
        } catch (IllegalArgumentException notAnIpAddress) {
            return false;   // e.g. "unknown" or a host name in the header: never one of our proxies
        }
    }
}
