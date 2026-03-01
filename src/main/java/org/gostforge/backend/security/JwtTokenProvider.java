package org.gostforge.backend.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import lombok.extern.slf4j.Slf4j;
import org.gostforge.backend.config.JwtProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Component
@Slf4j
public class JwtTokenProvider {

    private final JwtProperties props;
    private final StringRedisTemplate redis;

    private PrivateKey privateKey;
    private PublicKey publicKey;

    private static final String REFRESH_PREFIX = "refresh:";
    private static final String USER_REFRESH_PREFIX = "user_refresh:";

    public JwtTokenProvider(JwtProperties props, StringRedisTemplate redis) {
        this.props = props;
        this.redis = redis;
    }

    @PostConstruct
    void init() {
        try {
            this.privateKey = loadPrivateKey(props.getPrivateKey());
            this.publicKey = loadPublicKey(props.getPublicKey());
            log.info("JWT RS256 keys loaded successfully");
        } catch (Exception e) {
            log.warn("JWT keys not configured, generating ephemeral RSA key pair for dev");
            try {
                var gen = java.security.KeyPairGenerator.getInstance("RSA");
                gen.initialize(2048);
                var pair = gen.generateKeyPair();
                this.privateKey = pair.getPrivate();
                this.publicKey = pair.getPublic();
            } catch (Exception ex) {
                throw new RuntimeException("Cannot initialize JWT keys", ex);
            }
        }
    }

    public String createAccessToken(UUID userId, String username) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("username", username)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(props.getAccessTokenTtl())))
                .signWith(privateKey)
                .compact();
    }

    public String createRefreshToken(UUID userId) {
        String token = UUID.randomUUID().toString();
        Duration ttl = Duration.ofSeconds(props.getRefreshTokenTtl());

        redis.opsForValue().set(REFRESH_PREFIX + token, userId.toString(), ttl);
        redis.opsForSet().add(USER_REFRESH_PREFIX + userId, token);
        redis.expire(USER_REFRESH_PREFIX + userId, ttl);

        return token;
    }

    public Claims parseAccessToken(String token) {
        return Jwts.parser()
                .verifyWith(publicKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * Validates refresh token. Returns userId or empty.
     * Implements rotation: old token is consumed, caller must issue new pair.
     */
    public Optional<UUID> consumeRefreshToken(String token) {
        String key = REFRESH_PREFIX + token;
        String userId = redis.opsForValue().get(key);
        if (userId == null) return Optional.empty();

        // Delete old token (rotation)
        redis.delete(key);
        redis.opsForSet().remove(USER_REFRESH_PREFIX + userId, token);

        return Optional.of(UUID.fromString(userId));
    }

    public void revokeRefreshToken(String token) {
        String key = REFRESH_PREFIX + token;
        String userId = redis.opsForValue().get(key);
        if (userId != null) {
            redis.delete(key);
            redis.opsForSet().remove(USER_REFRESH_PREFIX + userId, token);
        }
    }

    public void revokeAllRefreshTokens(UUID userId) {
        String setKey = USER_REFRESH_PREFIX + userId;
        Set<String> tokens = redis.opsForSet().members(setKey);
        if (tokens != null) {
            for (String t : tokens) {
                redis.delete(REFRESH_PREFIX + t);
            }
        }
        redis.delete(setKey);
    }

    // ── Key loading helpers ─────────────────────────────────

    private PrivateKey loadPrivateKey(String pem) throws Exception {
        if (pem == null || pem.isBlank()) throw new IllegalArgumentException("No private key");
        String cleaned = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] decoded = Base64.getDecoder().decode(cleaned);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(decoded));
    }

    private PublicKey loadPublicKey(String pem) throws Exception {
        if (pem == null || pem.isBlank()) throw new IllegalArgumentException("No public key");
        String cleaned = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] decoded = Base64.getDecoder().decode(cleaned);
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(decoded));
    }
}
