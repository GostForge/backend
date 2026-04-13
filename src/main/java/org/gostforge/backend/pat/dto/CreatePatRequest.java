package org.gostforge.backend.pat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreatePatRequest {
    @NotBlank
    @Size(max = 100)
    private String name;

    @NotBlank
    @Size(max = 200)
    private String scopes = "api:full";

    @Size(max = 64)
    private String expiresAt; // ISO-8601 or null
}
