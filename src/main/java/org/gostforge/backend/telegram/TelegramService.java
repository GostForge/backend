package org.gostforge.backend.telegram;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gostforge.backend.auth.AuthService;
import org.gostforge.backend.auth.dto.AuthResponse;
import org.gostforge.backend.common.ApiException;
import org.gostforge.backend.user.User;
import org.gostforge.backend.user.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
@Slf4j
public class TelegramService {

    private final UserRepository userRepository;
    private final StringRedisTemplate redis;
    private final AuthService authService;

    @Value("${telegram.bot-token:}")
    private String botToken;

    private static final String LINK_CODE_PREFIX = "tg_link:";
    private static final Duration LINK_CODE_TTL = Duration.ofMinutes(10);

    // ── Link Code (user-facing, from auth portal) ────────────

    /**
     * Generate a link code for the authenticated user.
     * The user shares this code with the bot (/link GOST-XXXXXX).
     */
    @Transactional(readOnly = true)
    public String generateLinkCode(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User not found"));

        if (user.getTelegramChatId() != null) {
            throw ApiException.conflict("ALREADY_LINKED", "Telegram already linked");
        }

        String code = randomDigits(6);
        redis.opsForValue().set(LINK_CODE_PREFIX + code, userId.toString(), LINK_CODE_TTL);
        return code;
    }

    /**
     * Unlink telegram from current user.
     */
    @Transactional
    public void unlink(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User not found"));

        if (user.getTelegramChatId() == null) {
            throw ApiException.badRequest("NOT_LINKED", "Telegram is not linked");
        }

        user.setTelegramChatId(null);
        userRepository.save(user);
        log.info("Unlinked Telegram from user {}", userId);
    }

    // ── Verify Link (internal, called by bot) ────────────────

    /**
     * Bot calls this when user sends /link GOST-XXXXXX.
     * Validates code and links telegram_chat_id to the user.
     */
    @Transactional
    public VerifyLinkResponse verifyLink(String code, long telegramChatId) {
        String key = LINK_CODE_PREFIX + code;
        String userIdStr = redis.opsForValue().getAndDelete(key);

        if (userIdStr == null) {
            throw ApiException.badRequest("INVALID_CODE", "Link code is invalid or expired");
        }

        UUID userId = UUID.fromString(userIdStr);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User not found"));

        // Check if this chat is already linked to another user
        Optional<User> existing = userRepository.findByTelegramChatId(telegramChatId);
        if (existing.isPresent() && !existing.get().getId().equals(userId)) {
            throw ApiException.conflict("CHAT_ALREADY_LINKED",
                    "This Telegram account is already linked to another user");
        }

        user.setTelegramChatId(telegramChatId);
        userRepository.save(user);
        log.info("Linked Telegram chatId={} to user {}", telegramChatId, userId);

        return VerifyLinkResponse.builder()
                .userId(userId)
                .username(user.getUsername())
                .build();
    }

    // ── Mini App Auth ─────────────────────────────────────────

    /**
     * Validates Telegram initData and authenticates an already-linked user.
     * If chatId is not linked to any account, throws NOT_LINKED.
     * The frontend then shows login/register and passes initData to auto-link.
     */
    @Transactional(readOnly = true)
    public AuthResponse miniAppAuth(String initData) {
        TelegramUser tgUser = validateAndExtractUser(initData);
        long chatId = tgUser.id();

        Optional<User> existing = userRepository.findByTelegramChatId(chatId);
        if (existing.isEmpty()) {
            throw ApiException.notFound("NOT_LINKED", "Telegram account is not linked to any user");
        }

        return authService.buildAuthResponseForUser(existing.get());
    }

    /**
     * Validates initData and links the Telegram chatId to the given user.
     * Called after successful login/register when telegramInitData is present.
     */
    @Transactional
    public void autoLinkTelegram(UUID userId, String initData) {
        TelegramUser tgUser = validateAndExtractUser(initData);
        long chatId = tgUser.id();

        // Check if this chat is already linked to another user
        Optional<User> existingByChat = userRepository.findByTelegramChatId(chatId);
        if (existingByChat.isPresent()) {
            if (existingByChat.get().getId().equals(userId)) {
                return; // already linked to this user — no-op
            }
            throw ApiException.conflict("CHAT_ALREADY_LINKED",
                    "This Telegram account is already linked to another user");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User not found"));

        if (user.getTelegramChatId() != null && user.getTelegramChatId() != chatId) {
            throw ApiException.conflict("USER_ALREADY_LINKED",
                    "Your account is already linked to a different Telegram account");
        }

        user.setTelegramChatId(chatId);
        userRepository.save(user);
        log.info("Auto-linked Telegram chatId={} to user {} via Mini App", chatId, userId);
    }

    // ── Internal initData validation ─────────────────────────

    private TelegramUser validateAndExtractUser(String initData) {
        Map<String, String> params = parseInitData(initData);

        String receivedHash = params.remove("hash");
        if (receivedHash == null) {
            throw ApiException.badRequest("INVALID_INIT_DATA", "Missing hash in initData");
        }

        if (!validateTelegramHash(params, receivedHash)) {
            throw ApiException.unauthorized("Invalid initData signature");
        }

        String userJson = params.get("user");
        if (userJson == null) {
            throw ApiException.badRequest("INVALID_INIT_DATA", "Missing user in initData");
        }

        return parseTelegramUser(userJson);
    }

    // ── Helpers ──────────────────────────────────────────────

    private boolean validateTelegramHash(Map<String, String> params, String receivedHash) {
        if (botToken == null || botToken.isBlank()) {
            log.warn("Bot token not configured, skipping initData validation");
            return true; // dev mode
        }

        try {
            // Sort params and build data-check-string
            List<String> entries = new ArrayList<>();
            for (Map.Entry<String, String> entry : params.entrySet()) {
                entries.add(entry.getKey() + "=" + entry.getValue());
            }
            Collections.sort(entries);
            String dataCheckString = String.join("\n", entries);

            // HMAC-SHA256(secret_key, data_check_string)
            // secret_key = HMAC-SHA256("WebAppData", bot_token)
            Mac hmac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(
                    "WebAppData".getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            hmac.init(secretKeySpec);
            byte[] secretKey = hmac.doFinal(botToken.getBytes(StandardCharsets.UTF_8));

            Mac hmac2 = Mac.getInstance("HmacSHA256");
            hmac2.init(new SecretKeySpec(secretKey, "HmacSHA256"));
            byte[] hash = hmac2.doFinal(dataCheckString.getBytes(StandardCharsets.UTF_8));

            String computedHash = bytesToHex(hash);
            return MessageDigest.isEqual(
                    computedHash.getBytes(StandardCharsets.UTF_8),
                    receivedHash.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("Failed to validate initData: {}", e.getMessage());
            return false;
        }
    }

    private Map<String, String> parseInitData(String initData) {
        Map<String, String> params = new LinkedHashMap<>();
        for (String pair : initData.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                params.put(key, value);
            }
        }
        return params;
    }

    record TelegramUser(long id, String firstName, String lastName, String username) {}

    private TelegramUser parseTelegramUser(String json) {
        // Minimal JSON parsing to avoid extra dependencies
        long id = extractLong(json, "id");
        String firstName = extractString(json, "first_name");
        String lastName = extractString(json, "last_name");
        String username = extractString(json, "username");
        return new TelegramUser(id, firstName, lastName, username);
    }

    private long extractLong(String json, String key) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return 0;
        int colon = json.indexOf(':', idx);
        if (colon < 0) return 0;
        int start = colon + 1;
        while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '"')) start++;
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
        if (start == end) return 0;
        return Long.parseLong(json.substring(start, end));
    }

    private String extractString(String json, String key) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx);
        if (colon < 0) return null;
        int start = json.indexOf('"', colon + 1);
        if (start < 0) return null;
        int end = json.indexOf('"', start + 1);
        if (end < 0) return null;
        return json.substring(start + 1, end);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String randomDigits(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ThreadLocalRandom.current().nextInt(10));
        }
        return sb.toString();
    }
}
