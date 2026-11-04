package com.opspilot.gateway.filter;

import com.opspilot.gateway.config.ResilienceProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.gateway.server.mvc.common.MvcUtils;
import org.springframework.cloud.gateway.server.mvc.config.GatewayMvcProperties;
import org.springframework.cloud.gateway.server.mvc.config.RouteProperties;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

/**
 * Protects each route's calls to its service (architecture doc B2.4). Outside in:
 * <pre>Retry ( CircuitBreaker ( TimeLimiter ( the call ) ) )</pre>
 * <ul>
 *   <li><b>TimeLimiter:</b> stops waiting after the route's timeout → 504.</li>
 *   <li><b>CircuitBreaker:</b> counts failures over the last calls (no answer, too slow, a 5xx answer). Past the
 *       threshold it opens: calls are refused at once (503) for a while, then a few test calls decide whether it
 *       closes again. A broken service gets time to recover instead of more traffic.</li>
 *   <li><b>Retry:</b> GET and HEAD only, and only when the service couldn't be reached or answered 502 / 503 (both
 *       mean "nothing was done, try again"). Every attempt passes the circuit breaker, so an open circuit ends the
 *       retries at once.</li>
 * </ul>
 * Settings: gateway.resilience.* (ResilienceProperties); the instances themselves come from ResilienceConfig.
 */
@Component
public class RouteResilience {

    private static final Set<HttpMethod> SAFE_TO_REPEAT = Set.of(HttpMethod.GET, HttpMethod.HEAD);

    private final CircuitBreakerRegistry circuitBreakers;
    private final TimeLimiterRegistry timeLimiters;
    private final RetryRegistry retries;
    private final ExecutorService calls;

    public RouteResilience(CircuitBreakerRegistry circuitBreakers, TimeLimiterRegistry timeLimiters,
                           RetryRegistry retries, @Qualifier("routeCalls") ExecutorService calls,
                           ResilienceProperties properties, GatewayMvcProperties gateway) {
        this.circuitBreakers = circuitBreakers;
        this.timeLimiters = timeLimiters;
        this.retries = retries;
        this.calls = calls;

        // One of each per route, made now: their metrics show from the start (at 0), and each route gets its own timeout
        Set<String> routeIds = gateway.getRoutes().stream().map(RouteProperties::getId).collect(Collectors.toSet());
        for (String routeId : routeIds) {
            circuitBreakers.circuitBreaker(routeId);
            timeLimiters.timeLimiter(routeId, TimeLimiterConfig.custom()
                    .timeoutDuration(properties.timeoutFor(routeId))
                    .cancelRunningFuture(true)   // time is up: interrupt the call, don't let it run on unseen
                    .build());
            retries.retry(routeId);
        }
        for (String routeId : properties.timeouts().keySet()) {
            if (!routeIds.contains(routeId)) {   // e.g. a typo: better a failed start than a timeout that never applies
                throw new IllegalStateException("gateway.resilience.timeouts." + routeId + ": there is no route "
                        + routeId + " (routes: " + routeIds + ")");
            }
        }
    }

    public ServerResponse handle(String routeId, ServerRequest request, HandlerFunction<ServerResponse> next)
            throws Exception {
        CircuitBreaker circuitBreaker = circuitBreakers.circuitBreaker(routeId);
        TimeLimiter timeLimiter = timeLimiters.timeLimiter(routeId);

        // The call runs on its own virtual thread, so that this thread can stop waiting once the time is up
        Callable<ServerResponse> limited =
                () -> timeLimiter.executeFutureSupplier(() -> calls.submit(() -> next.handle(request)));
        Callable<ServerResponse> guarded = CircuitBreaker.decorateCallable(circuitBreaker, limited);

        if (!SAFE_TO_REPEAT.contains(request.method())) {
            return guarded.call();   // e.g. POST /api/orders: sending it twice could create two orders
        }
        Retry retry = retries.retry(routeId);
        return retry.executeCallable(() -> {
            closePreviousAnswer(request);
            return guarded.call();
        });
    }

    // Before another attempt: close the previous attempt's answer (its body was never read), so its connection is
    // freed. The gateway's own retry filter does the same.
    private static void closePreviousAnswer(ServerRequest request) {
        ClientHttpResponse previous = MvcUtils.getAttribute(request, MvcUtils.CLIENT_RESPONSE_ATTR);
        if (previous != null) {
            previous.close();
            MvcUtils.putAttribute(request, MvcUtils.CLIENT_RESPONSE_ATTR, null);
        }
    }
}
