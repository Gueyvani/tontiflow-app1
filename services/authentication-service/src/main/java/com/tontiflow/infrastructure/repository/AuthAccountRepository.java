package com.tontiflow.infrastructure.repository;

import com.tontiflow.domain.model.AuthAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Accès en persistance aux comptes d'authentification ({@link AuthAccount}).
 */
@Repository
public interface AuthAccountRepository extends JpaRepository<AuthAccount, UUID> {

    /**
     * Recherche un compte par son email (identifiant de connexion unique).
     *
     * @param email email recherché
     * @return le compte correspondant, ou {@link Optional#empty()} si aucun
     */
    Optional<AuthAccount> findByEmail(String email);

    /**
     * Indique si un compte existe déjà pour l'email donné.
     *
     * @param email email à vérifier
     * @return {@code true} si un compte utilise déjà cet email
     */
    boolean existsByEmail(String email);

    /**
     * Enregistre atomiquement un échec d'authentification (décision R21-D.3 ;
     * correction P1, atomicité) — {@code failed_attempts}/{@code
     * last_failed_login_at}/{@code locked_until} sont recalculés en un
     * <b>unique</b> {@code UPDATE} conditionnel, sans lecture Java
     * intermédiaire, afin d'éliminer tout risque de <i>lost update</i> sous
     * accès concurrent.
     *
     * <p>PostgreSQL (comme H2) verrouille la ligne pendant l'exécution d'un
     * {@code UPDATE} : deux exécutions concurrentes de cette méthode sur le
     * même compte sont nécessairement sérialisées par le moteur — la seconde
     * attend la fin de la première, puis relit et recalcule à partir de
     * l'état fraîchement validé (sémantique standard de READ COMMITTED pour
     * un {@code UPDATE} bloqué). Aucun incrément ne peut donc être perdu.</p>
     *
     * <p>Le {@code WHERE} exclut toute ligne actuellement sous verrouillage
     * temporisé actif ({@code locked_until > :now}) : dans ce cas la requête
     * ne modifie aucune ligne (0 rangée affectée) et le verrouillage n'est
     * <b>jamais prolongé</b> par des tentatives répétées pendant qu'il est
     * actif — la valeur de retour n'a pas besoin d'être interprétée par
     * l'appelant, la réponse restant dans tous les cas identique (générique).</p>
     *
     * <p>La fenêtre glissante ({@code failureWindow}) et le seuil de
     * verrouillage sont exprimés via des paramètres déjà calculés côté Java
     * ({@code now}, {@code windowStart}, {@code lockUntil}) — aucune
     * arithmétique de date n'est faite en SQL, ce qui garantit un
     * comportement identique sur H2 (tests) et PostgreSQL (production).</p>
     *
     * @param id                identifiant du compte
     * @param now               horodatage courant (horloge injectée du service, jamais {@code Instant.now()})
     * @param windowStart       {@code now - failureWindow} : au-delà, le compteur repart à 1
     * @param maxFailedAttempts seuil déclenchant le verrouillage
     * @param lockUntil         {@code now + lockDuration} : échéance du verrouillage si le seuil est atteint
     * @return le nombre de lignes affectées (0 si le compte est actuellement verrouillé, 1 sinon)
     */
    @Modifying
    @Query(value = """
            UPDATE auth_account
            SET failed_attempts = CASE
                    WHEN last_failed_login_at IS NULL OR last_failed_login_at < :windowStart THEN 1
                    ELSE failed_attempts + 1
                END,
                last_failed_login_at = :now,
                locked_until = CASE
                    WHEN (CASE WHEN last_failed_login_at IS NULL OR last_failed_login_at < :windowStart THEN 1
                               ELSE failed_attempts + 1 END) >= :maxFailedAttempts
                        THEN :lockUntil
                    ELSE locked_until
                END
            WHERE id = :id
              AND (locked_until IS NULL OR locked_until <= :now)
            """, nativeQuery = true)
    int registerFailedAttempt(@Param("id") UUID id, @Param("now") Instant now, @Param("windowStart") Instant windowStart,
                               @Param("maxFailedAttempts") int maxFailedAttempts, @Param("lockUntil") Instant lockUntil);

    /**
     * Remet atomiquement à zéro le compteur d'échecs (mot de passe correct),
     * <b>uniquement</b> si le compte n'est pas actuellement sous verrouillage
     * temporisé actif au moment réel de l'écriture — pas au moment de la
     * lecture antérieure de l'appelant, qui peut être périmée sous
     * concurrence (voir {@link #registerFailedAttempt} pour la garantie
     * d'atomicité sous-jacente, identique).
     *
     * @param id  identifiant du compte
     * @param now horodatage courant (horloge injectée du service)
     * @return le nombre de lignes affectées : {@code 1} si la remise à zéro a eu lieu,
     *         {@code 0} si le compte était verrouillé au moment de l'écriture (l'appelant
     *         doit alors traiter la tentative comme si le mot de passe était incorrect)
     */
    @Modifying
    @Query(value = """
            UPDATE auth_account
            SET failed_attempts = 0,
                last_failed_login_at = NULL,
                locked_until = NULL
            WHERE id = :id
              AND (locked_until IS NULL OR locked_until <= :now)
            """, nativeQuery = true)
    int resetFailedAttemptsIfNotLocked(@Param("id") UUID id, @Param("now") Instant now);
}
