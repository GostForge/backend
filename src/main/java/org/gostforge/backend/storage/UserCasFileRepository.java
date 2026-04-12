package org.gostforge.backend.storage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserCasFileRepository extends JpaRepository<UserCasFile, Integer> {

    Optional<UserCasFile> findByUserIdAndSha256(UUID userId, String sha256);

    List<UserCasFile> findByUserIdAndSha256In(UUID userId, List<String> hashes);

    @Query("SELECT COALESCE(SUM(f.sizeBytes), 0) FROM UserCasFile f WHERE f.userId = :userId")
    long sumSizeByUserId(UUID userId);

    List<UserCasFile> findByUserIdOrderByLastAccessedAtAsc(UUID userId);
}
