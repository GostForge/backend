package org.gostforge.backend.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gostforge.backend.config.RateLimitProperties;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimitProperties properties;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null || !path.startsWith("/api/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        RuleSelection selection = resolveRule(request);
        if (selection == null || selection.rule().isDisabled()) {
            chain.doFilter(request, response);
            return;
        }

        String subject = selection.perUser()
                ? resolveUserOrIp(request)
                : resolveClientIp(request);
        String bucketKey = selection.name() + ":" + subject;

        Bucket bucket = buckets.computeIfAbsent(bucketKey, k -> buildBucket(selection.rule()));
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            log.debug("Rate limit exceeded for {} [{}]", bucketKey, request.getRequestURI());
            response.sendError(429, "Rate limit exceeded");
            return;
        }

        response.setHeader("X-RateLimit-Remaining", String.valueOf(probe.getRemainingTokens()));
        chain.doFilter(request, response);
    }

    private RuleSelection resolveRule(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();

        if ("POST".equalsIgnoreCase(method) && "/api/v1/auth/login".equals(path)) {
            return new RuleSelection("login", properties.getLogin(), false);
        }
        if ("POST".equalsIgnoreCase(method) && "/api/v1/auth/register".equals(path)) {
            return new RuleSelection("registration", properties.getRegistration(), false);
        }
        if ("POST".equalsIgnoreCase(method) && "/api/v1/conversions".equals(path)) {
            return new RuleSelection("conversion", properties.getConversion(), true);
        }
        if (path != null && path.startsWith("/api/v1/")) {
            return new RuleSelection("api", properties.getApi(), true);
        }
        return null;
    }

    private Bucket buildBucket(RateLimitProperties.Rule rule) {
        Bandwidth limit = Bandwidth.builder()
            .capacity(rule.getCapacity())
            .refillIntervally(rule.getRefill(), Duration.ofMinutes(rule.getDurationMinutes()))
            .build();
        return Bucket.builder().addLimit(limit).build();
    }

    private String resolveUserOrIp(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()) {
            Object principal = auth.getPrincipal();
            if (principal != null) {
                String value = principal.toString();
                if (!value.isBlank() && !"anonymousUser".equalsIgnoreCase(value)) {
                    return "user:" + value;
                }
            }
        }
        return "ip:" + resolveClientIp(request);
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            int comma = forwardedFor.indexOf(',');
            return (comma >= 0 ? forwardedFor.substring(0, comma) : forwardedFor).trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }

    private record RuleSelection(String name, RateLimitProperties.Rule rule, boolean perUser) {
    }
}
