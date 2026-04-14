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
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String UNKNOWN_CLIENT = "unknown";

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

        String subject = switch (selection.subjectMode()) {
            case USER_OR_IP -> resolveUserOrIp(request);
            case CLIENT_IP -> resolveClientIp(request);
        };
        String bucketKey = selection.name() + ":" + subject;

        Bucket bucket = buckets.computeIfAbsent(bucketKey, k -> buildBucket(selection.rule()));
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            log.debug("Rate limit exceeded for {} [{}]", bucketKey, request.getRequestURI());
            long retryAfter = Math.max(1L, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()));
            response.setHeader("Retry-After", String.valueOf(retryAfter));
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
            return new RuleSelection("login", properties.getLogin(), SubjectMode.CLIENT_IP);
        }
        if ("POST".equalsIgnoreCase(method) && "/api/v1/auth/register".equals(path)) {
            return new RuleSelection("registration", properties.getRegistration(), SubjectMode.CLIENT_IP);
        }
        if ("POST".equalsIgnoreCase(method) && "/api/v1/conversions".equals(path)) {
            return new RuleSelection("conversion", properties.getConversion(), SubjectMode.USER_OR_IP);
        }
        if (path != null && path.startsWith("/api/v1/")) {
            return new RuleSelection("api", properties.getApi(), SubjectMode.USER_OR_IP);
        }
        return null;
    }

    private Bucket buildBucket(RateLimitProperties.Rule rule) {
        Bandwidth limit = Bandwidth.builder()
            .capacity(rule.getCapacity())
            .refillGreedy(rule.getRefill(), Duration.ofMinutes(rule.getDurationMinutes()))
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
        String remoteAddr = normalizeAddress(request.getRemoteAddr());
        if (remoteAddr == null) {
            return UNKNOWN_CLIENT;
        }

        if (!isTrustedProxySource(remoteAddr)) {
            return remoteAddr;
        }

        String realIp = request.getHeader("X-Real-IP");
        String normalizedRealIp = normalizeAddress(realIp);
        if (normalizedRealIp != null) {
            return normalizedRealIp;
        }

        String forwardedFor = request.getHeader("X-Forwarded-For");
        String normalizedForwardedIp = extractRightMostForwardedAddress(forwardedFor);
        if (normalizedForwardedIp != null) {
            return normalizedForwardedIp;
        }

        return remoteAddr;
    }

    private String extractRightMostForwardedAddress(String forwardedFor) {
        if (forwardedFor == null || forwardedFor.isBlank()) {
            return null;
        }

        String[] parts = forwardedFor.split(",");
        for (int i = parts.length - 1; i >= 0; i--) {
            String normalized = normalizeAddress(parts[i]);
            if (normalized != null) {
                return normalized;
            }
        }

        return null;
    }

    private String normalizeAddress(String rawAddress) {
        if (rawAddress == null) {
            return null;
        }

        String value = rawAddress.trim();
        if (value.isEmpty() || UNKNOWN_CLIENT.equalsIgnoreCase(value)) {
            return null;
        }

        if (value.startsWith("[") && value.contains("]")) {
            return value.substring(1, value.indexOf(']'));
        }

        int firstColon = value.indexOf(':');
        int lastColon = value.lastIndexOf(':');
        if (firstColon > 0 && firstColon == lastColon && value.contains(".")) {
            return value.substring(0, firstColon);
        }

        return value;
    }

    private boolean isTrustedProxySource(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }

        String normalized = address.toLowerCase();
        if (normalized.startsWith("fc") || normalized.startsWith("fd")) {
            return true;
        }

        try {
            InetAddress inetAddress = InetAddress.getByName(address);
            return inetAddress.isLoopbackAddress()
                    || inetAddress.isSiteLocalAddress()
                    || inetAddress.isLinkLocalAddress();
        } catch (UnknownHostException ignored) {
            return false;
        }
    }

    private record RuleSelection(String name, RateLimitProperties.Rule rule, SubjectMode subjectMode) {
    }

    private enum SubjectMode {
        USER_OR_IP,
        CLIENT_IP
    }
}
