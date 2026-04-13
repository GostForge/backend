package org.gostforge.backend.pat;

import lombok.RequiredArgsConstructor;
import org.gostforge.backend.common.ApiException;
import org.gostforge.backend.pat.dto.CreatePatRequest;
import org.gostforge.backend.pat.dto.PatResponse;
import org.gostforge.backend.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
public class PatService {

    private final PatRepository patRepository;
    private static final SecureRandom RANDOM = new SecureRandom();

    @Transactional
    public PatResponse create(UUID userId, CreatePatRequest req) {
        // Generate raw token: gstf_<base64url(32 random bytes)>
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String rawToken = "gstf_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String hash = SecurityUtils.sha256Hex(rawToken);

        Instant expiresAt = null;
        if (req.getExpiresAt() != null && !req.getExpiresAt().isBlank()) {
            expiresAt = Instant.parse(req.getExpiresAt());
        }

        String scopes = req.getScopes();
        if (scopes == null || scopes.isBlank()) {
            scopes = "api:full";
        }

        PersonalAccessToken pat = PersonalAccessToken.builder()
                .userId(userId)
                .name(req.getName() != null ? req.getName() : "unnamed")
                .tokenHash(hash)
                .scopes(scopes)
                .expiresAt(expiresAt)
                .build();

        pat = patRepository.save(pat);

        return PatResponse.builder()
                .id(pat.getId())
                .name(pat.getName())
                .token(rawToken) // shown only once
                .scopes(pat.getScopes())
                .expiresAt(pat.getExpiresAt())
                .createdAt(pat.getCreatedAt())
                .build();
    }

    @Transactional(readOnly = true)
    public List<PatResponse> listByUser(UUID userId) {
        return patRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(p -> PatResponse.builder()
                        .id(p.getId())
                        .name(p.getName())
                        .scopes(p.getScopes())
                        .lastUsed(p.getLastUsed())
                        .expiresAt(p.getExpiresAt())
                        .createdAt(p.getCreatedAt())
                        .build())
                .toList();
    }

    @Transactional
    public void revoke(UUID userId, UUID patId) {
        PersonalAccessToken pat = patRepository.findByIdAndUserId(patId, userId)
                .orElseThrow(() -> ApiException.notFound("Token not found"));
        patRepository.delete(pat);
    }
}
