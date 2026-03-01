package org.gostforge.backend.pat.dto;

import lombok.Data;

@Data
public class CreatePatRequest {
    private String name;
    private String scopes = "api:full";
    private String expiresAt; // ISO-8601 or null
}
