package org.gostforge.backend.pat.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PatResponse {
    private UUID id;
    private String name;
    private String token;      // only on creation
    private String scopes;
    private Instant lastUsed;
    private Instant expiresAt;
    private Instant createdAt;
}
