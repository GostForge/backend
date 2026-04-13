package org.gostforge.backend.conversion;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "conversion_jobs")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class ConversionJob {

    @Id
    @org.hibernate.annotations.UuidGenerator(style = org.hibernate.annotations.UuidGenerator.Style.TIME)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 30)
    @Builder.Default
    private String status = "PENDING";

    @Enumerated(EnumType.STRING)
    @Column(name = "conversion_chain", nullable = false, length = 20)
    @Builder.Default
    private ConversionChain conversionChain = ConversionChain.MD_TO_DOCX;

    @Column(name = "result_key", length = 500)
    private String resultKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "result_type", length = 20)
    private ConversionResultType resultType;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "error_stage", length = 50)
    private String errorStage;

    /** JSON array of conversion warnings (e.g. unsupported elements). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "warnings", columnDefinition = "jsonb")
    private List<String> warnings;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
