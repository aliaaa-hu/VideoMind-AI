package com.example.server.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Gives every API request a stable correlation id. Clients may provide a safe
 * {@code X-Request-Id}; otherwise the server creates one and always returns it.
 * The id is also attached to logs through MDC, so a failed upload or analysis
 * request can be followed across controller and service logs.
 */
@Component
public class RequestCorrelationFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Request-Id";
    private final boolean acceptClientRequestId;

    public RequestCorrelationFilter(
            @Value("${app.observability.accept-client-request-id:true}") boolean acceptClientRequestId) {
        this.acceptClientRequestId = acceptClientRequestId;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = resolveRequestId(request.getHeader(HEADER));
        MDC.put("requestId", requestId);
        response.setHeader(HEADER, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("requestId");
        }
    }

    private String resolveRequestId(String supplied) {
        if (acceptClientRequestId && supplied != null && supplied.matches("[A-Za-z0-9_-]{8,64}")) {
            return supplied;
        }
        return UUID.randomUUID().toString();
    }
}
