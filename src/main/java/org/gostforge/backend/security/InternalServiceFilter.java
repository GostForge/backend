package org.gostforge.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.gostforge.backend.user.User;
import org.gostforge.backend.user.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

@Component
@Slf4j
public class InternalServiceFilter extends OncePerRequestFilter {

    private final String internalApiKey;
    private final UserRepository userRepository;

    public InternalServiceFilter(
            @Value("${internal.api-key:gostforge_internal_dev}") String internalApiKey,
            UserRepository userRepository) {
        this.internalApiKey = internalApiKey;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        if (!request.getRequestURI().startsWith("/internal/")) {
            chain.doFilter(request, response);
            return;
        }

        String apiKey = request.getHeader("X-Internal-Api-Key");
        if (apiKey == null || !apiKey.equals(internalApiKey)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Invalid internal API key");
            return;
        }

        // Try to resolve user by Telegram chat ID
        String chatIdHeader = request.getHeader("X-Telegram-Chat-Id");
        if (chatIdHeader != null) {
            try {
                long chatId = Long.parseLong(chatIdHeader);
                Optional<User> user = userRepository.findByTelegramChatId(chatId);
                if (user.isEmpty()) {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"error\":\"Telegram account not linked\"}");
                    return;
                }
                var auth = new UsernamePasswordAuthenticationToken(
                        user.get().getId(), null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"),
                                new SimpleGrantedAuthority("ROLE_INTERNAL")));
                auth.setDetails("INTERNAL:telegram:" + chatId);
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (NumberFormatException e) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid X-Telegram-Chat-Id");
                return;
            }
        } else {
            // Internal service call without user context (e.g., callbacks)
            var auth = new UsernamePasswordAuthenticationToken(
                    "internal-service", null,
                    List.of(new SimpleGrantedAuthority("ROLE_INTERNAL")));
            SecurityContextHolder.getContext().setAuthentication(auth);
        }

        chain.doFilter(request, response);
    }
}
