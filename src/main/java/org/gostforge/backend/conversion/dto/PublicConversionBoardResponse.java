package org.gostforge.backend.conversion.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
@Builder
public class PublicConversionBoardResponse {

    private Instant generatedAt;

    private long totalJobs;
    private long activeJobs;
    private long completedJobs;
    private long failedJobs;

    private long submittedLast24h;
    private long completedLast24h;
    private long failedLast24h;

    private List<Item> recent;

    @Data
    @Builder
    public static class Item {
        private String publicId;
        private String status;
        private String conversionChain;
        private Instant createdAt;
        private Instant completedAt;
        private Long durationMs;
        private int warningCount;
        private boolean hasError;
    }
}
