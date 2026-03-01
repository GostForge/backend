package org.gostforge.backend.pat;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PatRepository extends JpaRepository<PersonalAccessToken, UUID> {

    Optional<PersonalAccessToken> findByTokenHash(String tokenHash);

    List<PersonalAccessToken> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<PersonalAccessToken> findByIdAndUserId(UUID id, UUID userId);
}
