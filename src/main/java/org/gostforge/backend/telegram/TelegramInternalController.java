package org.gostforge.backend.telegram;

import lombok.RequiredArgsConstructor;
import org.gostforge.backend.auth.dto.AuthResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Internal endpoints for Telegram bot.
 * Secured by InternalServiceFilter (X-Internal-Api-Key).
 */
@RestController
@RequestMapping("/internal/telegram")
@RequiredArgsConstructor
public class TelegramInternalController {

    private final TelegramService telegramService;

    /**
     * POST /internal/telegram/verify-link
     * Bot calls this when user sends /link GOST-XXXXXX
     */
    @PostMapping("/verify-link")
    public ResponseEntity<VerifyLinkResponse> verifyLink(@RequestBody Map<String, Object> body) {
        String code = (String) body.get("code");
        Number chatIdNum = (Number) body.get("telegramChatId");

        if (code == null || chatIdNum == null) {
            return ResponseEntity.badRequest().build();
        }

        VerifyLinkResponse resp = telegramService.verifyLink(code, chatIdNum.longValue());
        return ResponseEntity.ok(resp);
    }

    /**
     * POST /internal/telegram/mini-app-auth
     * Validates initData and returns JWT tokens.
     */
    @PostMapping("/mini-app-auth")
    public ResponseEntity<AuthResponse> miniAppAuth(@RequestBody Map<String, String> body) {
        String initData = body.get("initData");
        if (initData == null || initData.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        AuthResponse resp = telegramService.miniAppAuth(initData);
        return ResponseEntity.ok(resp);
    }
}
