package org.gostforge.backend.conversion;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConversionJobRepository extends JpaRepository<ConversionJob, UUID> {

       interface StatusCountRow {
              String getStatus();
              long getCnt();
       }

    Optional<ConversionJob> findByIdAndUserId(UUID id, UUID userId);

    @Query("SELECT j FROM ConversionJob j WHERE j.userId = :userId AND j.status IN :statuses")
    List<ConversionJob> findActiveJobs(UUID userId, List<String> statuses);

       @Query("SELECT j FROM ConversionJob j ORDER BY j.createdAt DESC")
       List<ConversionJob> findRecent(Pageable pageable);

        @Query("SELECT j.status AS status, COUNT(j) AS cnt FROM ConversionJob j GROUP BY j.status")
        List<StatusCountRow> countByStatusGrouped();

        @Query("SELECT j.status AS status, COUNT(j) AS cnt FROM ConversionJob j WHERE j.createdAt >= :after GROUP BY j.status")
        List<StatusCountRow> countByStatusGroupedAfter(Instant after);

    @Modifying
    @Query("UPDATE ConversionJob j SET j.status = 'FAILED', j.errorStage = 'TIMEOUT', " +
           "j.errorMessage = 'Processing timeout', j.updatedAt = CURRENT_TIMESTAMP " +
           "WHERE j.status IN :statuses AND j.startedAt < :before")
    int markTimedOut(List<String> statuses, Instant before);

    @Modifying
    @Query("UPDATE ConversionJob j SET j.status = 'FAILED', j.errorStage = 'CRASH_RECOVERY', " +
           "j.errorMessage = 'Server restarted during processing', j.updatedAt = CURRENT_TIMESTAMP " +
           "WHERE j.status IN :statuses")
    int markCrashRecovery(List<String> statuses);
}
