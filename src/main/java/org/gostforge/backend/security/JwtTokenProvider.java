package org.gostforge.backend.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import lombok.extern.slf4j.Slf4j;
import org.gostforge.backend.config.JwtProperties;
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
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
public class JwtTokenProvider {

    private final JwtProperties props;

    private PrivateKey privateKey;
    private PublicKey publicKey;

    public JwtTokenProvider(JwtProperties props) {
        this.props = props;
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

    

    public Claims parseAccessToken(String token) {
        return Jwts.parser()
                .verifyWith(publicKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
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
