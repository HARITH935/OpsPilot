package com.opspilot.gateway.filter;

import org.springframework.cloud.gateway.server.mvc.common.MvcUtils;
import org.springframework.cloud.gateway.server.mvc.common.Shortcut;
import org.springframework.web.servlet.function.HandlerFilterFunction;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * Our own gateway filter, usable in application.yml like the built-in ones: {@code filters: - Resilience=orders}.
 * The gateway finds it through the FilterSupplier bean in ResilienceConfig: each public static method returning a
 * HandlerFilterFunction becomes a filter named after the method.
 *
 * <p>Why not the built-in CircuitBreaker filter? Without a fallback it turns every other error into a plain 500
 * (a stopped service would no longer be 503), and it drops a service's own 5xx answer. And the built-in Retry filter
 * has no jitter. RouteResilience does both the way B2.4 asks.
 */
public final class ResilienceFilterFunctions {

    private ResilienceFilterFunctions() {
    }

    @Shortcut   // allows the short form "Resilience=orders": the value is the route id
    public static HandlerFilterFunction<ServerResponse, ServerResponse> resilience(String routeId) {
        // A static method can't be given beans, so it looks RouteResilience up when a request comes (a cheap lookup)
        return (request, next) -> MvcUtils.getApplicationContext(request).getBean(RouteResilience.class)
                .handle(routeId, request, next);
    }
}
