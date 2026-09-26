package com.tontiflow.infrastructure.repository;

import com.tontiflow.domain.model.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
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

    /**
     * Révoque en masse <b>toutes</b> les familles de rotation encore actives
     * d'un compte (décision R21-RD, D6) — contrairement à {@link #revokeFamily},
     * qui ne cible qu'une seule famille (celle dérivée d'un token présenté),
     * cette méthode cible directement {@code account_id} : nécessaire car
     * {@code RefreshTokenService#issue} ouvre une nouvelle famille à chaque
     * émission — un compte peut donc posséder plusieurs familles actives
     * simultanément (plusieurs appareils/sessions). Utilise l'index déjà
     * présent {@code idx_refresh_token_account_id} (migration V3, prévu dès
     * l'origine pour d'éventuelles « recherches/purges par compte »).
     *
     * @param accountId compte dont toutes les familles actives doivent être révoquées
     * @param revokedAt horodatage de révocation à appliquer (horloge injectée, jamais {@code Instant.now()})
     * @return le nombre de tokens effectivement révoqués par cet appel
     */
    @Modifying
    @Query(value = """
            UPDATE refresh_token
            SET revoked_at = :revokedAt
            WHERE account_id = :accountId
              AND revoked_at IS NULL
            """, nativeQuery = true)
    int revokeAllActiveForAccount(@Param("accountId") UUID accountId, @Param("revokedAt") Instant revokedAt);

    /**
     * Lot de lignes purgeables (décision F-2) : une ligne l'est si elle est expirée avant
     * {@code cutoff} <b>et</b> si <b>aucun</b> jeton de sa famille n'a {@code expires_at >= cutoff}
     * (famille entièrement expirée au-delà de la marge). Un ancien jeton expiré dont la famille a
     * encore un successeur non expiré n'est donc jamais retourné : {@code /logout} avec ce jeton
     * doit continuer à révoquer ce successeur.
     *
     * <p>Requête native SQL standard (PostgreSQL et H2), sans construction propre à un moteur : le
     * parcours suit l'index {@code idx_refresh_token_expires_at} dans l'ordre
     * {@code (expires_at, id)} et un curseur ({@code afterExpiresAt}, {@code afterId}) évite de
     * relire, à chaque lot d'une même passe, les lignes expirées mais non purgeables. Le curseur est une
     * comparaison de ligne SQL {@code (expires_at, id) > (?, ?)} : PostgreSQL la traduit en borne de départ
     * de l'index (une forme {@code a > ? OR (a = ? AND b > ?)} obligerait à relire le préfixe de l'index à
     * chaque lot) ; H2 2.2 la supporte avec la même sémantique lexicographique.</p>
     *
     * <p><b>Multi-instance</b> : {@code FOR UPDATE SKIP LOCKED} verrouille les lignes du lot jusqu'à
     * la fin de la transaction du lot (le {@code DELETE} suivant est dans la même transaction) et fait
     * sauter les lignes déjà verrouillées par une autre instance : deux passes simultanées traitent des
     * lots disjoints, sans attente ni interblocage. Supporté par PostgreSQL et par H2 (vérifié par les
     * tests). Le verrou ne porte que sur {@code r} (la sous-requête {@code NOT EXISTS} n'est pas
     * verrouillée) et ne peut jamais s'opposer à {@code /login} ou {@code /refresh}, qui ne touchent que
     * des lignes de familles vivantes. {@code expires_at} n'est jamais modifié et une famille entièrement
     * expirée ne reçoit plus de successeur ({@code rotate} refuse un jeton expiré) : une ligne
     * retournée reste purgeable jusqu'à sa suppression. Un lot partiel signifie donc que les candidats
     * non verrouillés sont épuisés.</p>
     */
    @Query(value = """
            SELECT r.*
            FROM refresh_token r
            WHERE r.expires_at < :cutoff
              AND (r.expires_at, r.id) > (:afterExpiresAt, :afterId)
              AND NOT EXISTS (
                  SELECT 1 FROM refresh_token s
                  WHERE s.family_id = r.family_id
                    AND s.expires_at >= :cutoff)
            ORDER BY r.expires_at, r.id
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<RefreshToken> findPurgeableBatch(@Param("cutoff") Instant cutoff,
                                          @Param("afterExpiresAt") Instant afterExpiresAt,
                                          @Param("afterId") UUID afterId,
                                          @Param("batchSize") int batchSize);

    /**
     * Supprime physiquement les lignes données (décision F-2). Le prédicat {@code expires_at <
     * cutoff} est redondant avec {@link #findPurgeableBatch} : garde-fou pour ne jamais supprimer
     * un jeton encore conservable, même en cas de mauvais usage. Idempotent : des identifiants
     * déjà supprimés (autre instance) ne provoquent aucune erreur.
     */
    @Modifying
    @Query("delete from RefreshToken r where r.id in :ids and r.expiresAt < :cutoff")
    int deleteExpiredByIds(@Param("ids") Collection<UUID> ids, @Param("cutoff") Instant cutoff);
}
