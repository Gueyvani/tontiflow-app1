package com.tontiflow.infrastructure.repository;

import com.tontiflow.domain.model.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Accès en persistance aux Refresh Tokens ({@link RefreshToken}).
 */
@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Révoque en masse tous les tokens encore actifs d'une famille (mise à jour
     * directe, sans charger les entités individuellement).
     *
     * @param familyId  lignée de rotation à révoquer entièrement
     * @param revokedAt horodatage de révocation à appliquer
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RefreshToken r set r.revokedAt = :revokedAt where r.familyId = :familyId and r.revokedAt is null")
    void revokeFamily(@Param("familyId") UUID familyId, @Param("revokedAt") Instant revokedAt);
}
