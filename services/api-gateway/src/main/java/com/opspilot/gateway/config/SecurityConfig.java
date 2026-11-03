package com.opspilot.gateway.config;

import com.opspilot.gateway.exception.SecurityProblemHandler;
import com.opspilot.gateway.service.TokenService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Who may do what at the gateway (ADR 0005): reading is open, placing an order is open, changing the product catalogue
 * needs the ADMIN role. Spring Security runs as a filter in front of the routes, so a refused request never reaches
 * a service. A Bearer token, if one is sent, is always checked: a bad token is refused even on an open path.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityProblemHandler problems) throws Exception {
        http
                // First matching rule wins, so the specific rules come before the catch-all
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.GET, "/api/products/**").permitAll()
                        .requestMatchers(HttpMethod.HEAD, "/api/products/**").permitAll()
                        .requestMatchers("/api/products/**").hasRole("ADMIN")   // POST (and any later PUT / DELETE)
                        .anyRequest().permitAll())   // orders, payments, /auth/token, the public key, health
                // Read "Authorization: Bearer <jwt>", check it with the JwtDecoder bean (JwtConfig), take the roles
                .oauth2ResourceServer(server -> server
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(rolesFromToken()))
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems)
                        // /.well-known/oauth-protected-resource (RFC 9728) tells clients how to send tokens. Spring's
                        // default also claims tokens bound to client certificates (mutual TLS): we don't do that.
                        .protectedResourceMetadata(metadata -> metadata.protectedResourceMetadataCustomizer(
                                about -> about.tlsClientCertificateBoundAccessTokens(false))))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(problems)   // no token where one is needed → 401
                        .accessDeniedHandler(problems))       // wrong role → 403
                // Every request brings its own token, so the gateway keeps no login sessions in memory
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // CSRF tricks a browser into sending its cookies to us. The token travels in a header, which another
                // site can't make a browser add, so there is nothing to protect yet. (Phase 12 sends it in a cookie:
                // then SameSite=Lax and this setting are looked at again.)
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable);
        return http.build();
    }

    // The token's "roles" claim (["ADMIN"]) becomes the authority ROLE_ADMIN, which hasRole("ADMIN") looks for
    private static JwtAuthenticationConverter rolesFromToken() {
        JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName(TokenService.ROLES_CLAIM);
        roles.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(roles);
        return converter;
    }
}
