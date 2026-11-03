package com.opspilot.gateway.filter;

import com.opspilot.gateway.config.RateLimitProperties;
import com.opspilot.gateway.config.RateLimitProperties.Limit;
import com.opspilot.gateway.exception.ProblemResponses;
import com.opspilot.gateway.service.ClientIpResolver;
import com.opspilot.gateway.service.SlidingWindowRateLimiter;
import com.opspilot.gateway.service.SlidingWindowRateLimiter.Decision;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Counts every request to /api/** and /auth/** against its caller's budget, and answers 429 Too Many Requests once
 * the budget is used up (architecture doc B2.4). Runs as a servlet filter after Spring Security, so a token has
 * already been checked: a caller with a valid token is counted by the token's "sub", everyone else by IP address.
 * Each route (orders, products, payments) has its own budget, and login has a stricter one. Health checks, metrics
 * and the public key are never limited.
 *
 * <p>Fail-open: if Redis can't be reached, the request goes through unlimited. The shop keeps working, and the
 * problem shows in the log (a WARN at most every 30 s) and in gateway_ratelimit_redis_errors_total.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    // The routes in application.yml. Any other /api path counts as "other", so a client inventing paths can't
    // create endless metric labels (each label value costs memory) or escape the limit.
    private static final Set<String> ROUTES = Set.of("orders", "products", "payments");
    private static final Duration WARN_EVERY = Duration.ofSeconds(30);

    private final SlidingWindowRateLimiter limiter;
    private final ClientIpResolver clientIps;
    private final RateLimitProperties properties;
    private final ProblemResponses problems;
    private final MeterRegistry meters;
    private final Counter redisErrors;
    private final AtomicBoolean redisDown = new AtomicBoolean(false);
    private final AtomicReference<Instant> lastWarning = new AtomicReference<>(Instant.EPOCH);

    public RateLimitFilter(SlidingWindowRateLimiter limiter, ClientIpResolver clientIps, RateLimitProperties properties,
                           ProblemResponses problems, MeterRegistry meters) {
        this.limiter = limiter;
        this.clientIps = clientIps;
        this.properties = properties;
        this.problems = problems;
        this.meters = meters;
        // Registered now, so it shows 0 (not "missing") while all is well
        this.redisErrors = Counter.builder("gateway.ratelimit.redis.errors")
                .description("Rate-limit checks that failed because Redis could not be reached (request let through)")
                .register(meters);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.enabled() || routeOf(request.getRequestURI()) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String route = routeOf(request.getRequestURI());
        Caller caller = callerOf(request);
        Limit limit = route.equals("login") ? properties.login()
                : caller.keyType().equals("user") ? properties.user() : properties.anonymous();

        Decision decision;
        try {
            decision = limiter.tryAcquire("ratelimit:" + route + ":" + caller.keyType() + ":" + caller.id(), limit);
        } catch (DataAccessException e) {
            redisUnreachable(e);
            chain.doFilter(request, response);   // fail-open: rather unlimited than down
            return;
        }
        redisReachable();

        // Every answer tells the client where it stands, so a well-behaved client can slow down by itself
        response.setHeader("X-RateLimit-Limit", String.valueOf(decision.limit()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
        response.setHeader("X-RateLimit-Reset", String.valueOf(decision.resetInSeconds()));   // seconds from now
        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }

        Counter.builder("gateway.ratelimit.rejected")
                .description("Requests refused with 429 Too Many Requests")
                .tag("route", route)
                .tag("key_type", caller.keyType())
                .register(meters)   // the same counter is returned every time for the same name and tags
                .increment();
        response.setHeader("Retry-After", String.valueOf(decision.resetInSeconds()));
        problems.write(response, request, HttpStatus.TOO_MANY_REQUESTS, "Too many requests",
                "At most " + limit.requests() + " requests per " + describe(limit.window()) + " here"
                        + (caller.keyType().equals("user") ? "" : " without logging in")
                        + ". Try again in " + decision.resetInSeconds() + " s.");
    }

    // /api/orders/... → "orders", /auth/token → "login", anything else (health, metrics, public key) → null: not limited
    static String routeOf(String path) {
        if (path.startsWith("/auth/")) {
            return "login";
        }
        if (!path.startsWith("/api/")) {
            return null;
        }
        String first = path.substring("/api/".length()).split("/", 2)[0];
        return ROUTES.contains(first) ? first : "other";
    }

    private Caller callerOf(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token) {   // only a token Spring Security accepted
            return new Caller("user", token.getName());   // the token's "sub", e.g. demo:admin
        }
        return new Caller("ip", clientIps.clientIp(request));
    }

    private void redisUnreachable(DataAccessException e) {
        redisErrors.increment();
        redisDown.set(true);
        Instant now = Instant.now();
        Instant last = lastWarning.get();
        if (now.isAfter(last.plus(WARN_EVERY)) && lastWarning.compareAndSet(last, now)) {   // one thread warns
            log.warn("Redis can't be reached, so requests are NOT rate limited until it is back (fail-open): {}",
                    e.getMostSpecificCause().toString());   // the real cause, e.g. "...: Connection reset"
        }
    }

    private void redisReachable() {
        if (redisDown.compareAndSet(true, false)) {
            log.info("Redis is reachable again: rate limiting is back on");
        }
    }

    private static String describe(Duration window) {
        return window.toMillis() % 60_000 == 0 ? window.toMinutes() + " min"
                : window.toMillis() % 1000 == 0 ? window.toSeconds() + " s" : window.toMillis() + " ms";
    }

    private record Caller(String keyType, String id) {   // keyType "user" or "ip"
    }
}
