package org.gostforge.backend.conversion.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JobStatusResponse {
    private UUID jobId;
    private String status;
    private Integer queuePosition;
    private String conversionChain;
    private String errorStage;
    private String errorMessage;
    private List<String> warnings;
    private Instant createdAt;
    private Instant updatedAt;
}
