package org.gostforge.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LoginRequest {
    @NotBlank
    private String login; // username or email

    @NotBlank
    private String password;

    /** Optional Telegram Mini App initData — if present, auto-links Telegram after login. */
    private String telegramInitData;
}
