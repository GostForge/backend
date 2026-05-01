package org.gostforge.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LoginRequest {
    @NotBlank
    private String login; // username or email

    @NotBlank
    private String password;
}
