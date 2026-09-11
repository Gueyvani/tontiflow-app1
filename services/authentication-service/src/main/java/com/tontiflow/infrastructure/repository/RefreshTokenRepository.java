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

    /**
     * Consomme atomiquement un Refresh Token (correction concurrence R21-D.6,
     * constat D4-02/R21-D.4) : un <b>unique</b> {@code UPDATE} conditionnel,
     * sans lecture Java intermédiaire, marque le token comme révoqué
     * <b>uniquement s'il ne l'était pas déjà</b>. Élimine la fenêtre de course
     * de l'ancien mécanisme ({@code findByTokenHash} suivi d'un test
     * {@code revokedAt != null} puis d'une mutation d'entité différée au
     * commit) : deux appels concurrents à cette méthode sur le même {@code id}
     * sont sérialisés par le verrou de ligne pris par l'{@code UPDATE}
     * lui-même (PostgreSQL comme H2, sémantique standard READ COMMITTED) — au
     * plus un seul peut affecter une ligne.
     *
     * @param id  identifiant du Refresh Token présenté (obtenu via {@link #findByTokenHash})
     * @param now horodatage de révocation (horloge injectée du service, jamais {@code Instant.now()})
     * @return {@code 1} si ce token n'était pas encore révoqué et vient de l'être par cet
     *         appel (l'appelant peut poursuivre la rotation) ; {@code 0} s'il était déjà
     *         révoqué au moment de l'écriture (réutilisation — l'appelant doit traiter
     *         ceci comme une détection de vol, sans émettre aucun nouveau token)
     */
    @Modifying
    @Query(value = """
            UPDATE refresh_token
            SET revoked_at = :now
            WHERE id = :id
              AND revoked_at IS NULL
            """, nativeQuery = true)
    int consumeIfActive(@Param("id") UUID id, @Param("now") Instant now);
}
