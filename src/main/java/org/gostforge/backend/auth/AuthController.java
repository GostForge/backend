package org.gostforge.backend.auth;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gostforge.backend.auth.dto.AuthResponse;
import org.gostforge.backend.auth.dto.LoginRequest;
import org.gostforge.backend.auth.dto.RefreshRequest;
import org.gostforge.backend.auth.dto.RegisterRequest;
import org.gostforge.backend.telegram.TelegramService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final AuthService authService;
    private final TelegramService telegramService;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        AuthResponse response = authService.register(request);
        autoLinkIfInitData(response, request.getTelegramInitData());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthResponse response = authService.login(request);
        autoLinkIfInitData(response, request.getTelegramInitData());
        return ResponseEntity.ok(response);
    }

    /**
     * POST /api/v1/auth/telegram
     * Mini App auto-auth: validates initData, returns JWT if chatId is already linked.
     * Returns 404 NOT_LINKED if the Telegram account is not linked to any user.
     */
    @PostMapping("/telegram")
    public ResponseEntity<AuthResponse> telegramAuth(@RequestBody Map<String, String> body) {
        String initData = body.get("initData");
        if (initData == null || initData.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(telegramService.miniAppAuth(initData));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(authService.refresh(request.getRefreshToken()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.getRefreshToken());
        return ResponseEntity.ok().build();
    }

    /**
     * If telegramInitData is present, auto-link Telegram chatId to the authenticated user.
     */
    private void autoLinkIfInitData(AuthResponse response, String telegramInitData) {
        if (telegramInitData == null || telegramInitData.isBlank()) return;
        try {
            telegramService.autoLinkTelegram(response.getUser().getId(), telegramInitData);
            response.getUser().setTelegramLinked(true);
        } catch (Exception e) {
            log.warn("Auto-link Telegram failed for user {}: {}",
                    response.getUser().getId(), e.getMessage());
            // Non-fatal — auth succeeded, just linking failed
        }
    }
}
