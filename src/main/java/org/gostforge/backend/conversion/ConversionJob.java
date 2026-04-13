package org.gostforge.backend.conversion;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
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

    @Column(name = "conversion_chain", nullable = false, length = 20)
    @Builder.Default
    private String conversionChain = "MD_TO_DOCX";

    @Column(name = "merged_md_key", length = 500)
    private String mergedMdKey;

    @Column(name = "docx_key", length = 500)
    private String docxKey;

    @Column(name = "pdf_key", length = 500)
    private String pdfKey;

    @Column(name = "md2gost_job_id", length = 100)
    private String md2gostJobId;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "error_stage", length = 50)
    private String errorStage;

    /** JSON array of conversion warnings (e.g. unsupported elements). */
    @Column(name = "warnings")
    private String warnings;

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
