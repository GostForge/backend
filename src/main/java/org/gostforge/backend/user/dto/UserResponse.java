package org.gostforge.backend.user.dto;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
public class UserResponse {
    private UUID id;
    private String username;
    private String email;
    private String displayName;
    private boolean telegramLinked;
    private int storageQuotaMb;
}
