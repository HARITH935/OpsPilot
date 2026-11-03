package com.opspilot.gateway.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes an RFC 9457 Problem Details body straight into the servlet response. For code that runs before Spring MVC
 * (Spring Security's handlers, the rate-limit filter), where returning a ProblemDetail object isn't possible.
 * Same fields as the services' errors: {"title","status","detail","instance"}.
 */
@Component
public class ProblemResponses {

    private final JsonMapper json;

    public ProblemResponses(JsonMapper json) {
        this.json = json;
    }

    public void write(HttpServletResponse response, HttpServletRequest request, HttpStatus status, String title,
                      String detail) throws IOException {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("title", title);
        problem.put("status", status.value());
        problem.put("detail", detail);
        problem.put("instance", request.getRequestURI());
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(json.writeValueAsString(problem));
    }
}
