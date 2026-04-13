package org.gostforge.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
@Slf4j
public class InternalServiceFilter extends OncePerRequestFilter {

    private final String internalApiKey;

    public InternalServiceFilter(
            @Value("${internal.api-key:}") String internalApiKey) {
        this.internalApiKey = internalApiKey;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String path = request.getRequestURI();
        boolean isInternalRequest = path != null && path.startsWith("/internal/");
        if (!isInternalRequest) {
            chain.doFilter(request, response);
            return;
        }

        if (internalApiKey == null || internalApiKey.isBlank()) {
            log.error("internal.api-key is not configured; rejecting internal request: {}", path);
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Internal API key is not configured");
            return;
        }

        String apiKey = request.getHeader("X-Internal-Api-Key");
        if (apiKey == null) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Missing internal API key");
            return;
        }

        if (!apiKey.equals(internalApiKey)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Invalid internal API key");
            return;
        }

        // Internal service call without user context (e.g., callbacks)
        var auth = new UsernamePasswordAuthenticationToken(
            "internal-service", null,
            List.of(new SimpleGrantedAuthority("ROLE_INTERNAL")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        chain.doFilter(request, response);
    }
}
