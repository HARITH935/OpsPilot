package com.opspilot.gateway.exception;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * What a client gets when Spring Security turns a request away, as RFC 9457 Problem Details like every other error:
 * 401 = "who are you?" (no token, or a broken / expired / forged one), 403 = "I know who you are, but your role may
 * not do this". Spring's Bearer-token helpers set the status and the standard WWW-Authenticate header (RFC 6750);
 * this class adds the JSON body (ProblemResponses).
 */
@Component
public class SecurityProblemHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(SecurityProblemHandler.class);

    private final AuthenticationEntryPoint bearerEntryPoint = new BearerTokenAuthenticationEntryPoint();
    private final AccessDeniedHandler bearerAccessDenied = new BearerTokenAccessDeniedHandler();
    private final ProblemResponses problems;

    public SecurityProblemHandler(ProblemResponses problems) {
        this.problems = problems;
    }

    // 401. A token that was sent but failed the check is an OAuth2AuthenticationException (e.g. "Jwt expired at ...")
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException, ServletException {
        bearerEntryPoint.commence(request, response, ex);   // 401 + WWW-Authenticate: Bearer ...
        if (ex instanceof OAuth2AuthenticationException) {
            log.info("{} {} refused, bad token: {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
            problems.write(response, request, HttpStatus.UNAUTHORIZED, "Invalid token",
                    "The token is invalid or has expired: log in again for a new one");
        } else {
            problems.write(response, request, HttpStatus.UNAUTHORIZED, "Login required",
                    "This request needs a token: log in, then send it as 'Authorization: Bearer <token>'");
        }
    }

    // 403: the token is fine, the role is not enough (e.g. VIEWER on POST /api/products)
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException, ServletException {
        bearerAccessDenied.handle(request, response, ex);   // 403 + WWW-Authenticate: Bearer error="insufficient_scope"
        log.info("{} {} refused: role not allowed", request.getMethod(), request.getRequestURI());
        problems.write(response, request, HttpStatus.FORBIDDEN, "Not allowed",
                "Your token is valid, but your role may not do this");
    }
}
