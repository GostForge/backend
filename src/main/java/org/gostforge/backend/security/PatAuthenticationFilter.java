package org.gostforge.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gostforge.backend.pat.PatRepository;
import org.gostforge.backend.pat.PersonalAccessToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class PatAuthenticationFilter extends OncePerRequestFilter {

    private final PatRepository patRepository;
    private static final long LAST_USED_UPDATE_INTERVAL_SECONDS = 60;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            chain.doFilter(request, response);
            return;
        }

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer gstf_")) {
            chain.doFilter(request, response);
            return;
        }

        String rawToken = header.substring(7); // "gstf_..."
        try {
            String hash = SecurityUtils.sha256Hex(rawToken);
            var optPat = patRepository.findByTokenHash(hash);

            if (optPat.isEmpty()) {
                chain.doFilter(request, response);
                return;
            }

            PersonalAccessToken pat = optPat.get();

            if (pat.isExpired()) {
                log.debug("PAT expired: {}", pat.getId());
                chain.doFilter(request, response);
                return;
            }

            Instant now = Instant.now();
            Instant updateThreshold = now.minusSeconds(LAST_USED_UPDATE_INTERVAL_SECONDS);
            if (pat.getLastUsed() == null || pat.getLastUsed().isBefore(updateThreshold)) {
                pat.setLastUsed(now);
                patRepository.save(pat);
            }

            List<SimpleGrantedAuthority> authorities = new ArrayList<>();
            authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
            for (String scope : pat.getScopes().split(",")) {
                authorities.add(new SimpleGrantedAuthority("SCOPE_" + scope.trim()));
            }

            var auth = new UsernamePasswordAuthenticationToken(
                    pat.getUserId(), null, authorities);
            auth.setDetails("PAT:" + pat.getName());
            SecurityContextHolder.getContext().setAuthentication(auth);
        } catch (Exception e) {
            log.debug("Invalid PAT: {}", e.getMessage());
        }

        chain.doFilter(request, response);
    }
}
