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
     * Enregistre atomiquement un échec d'authentification, sous forme d'un
     * <b>ralentissement progressif</b> (décision R21-D.5, remplace le
     * verrouillage dur de R21-D.3 — corrige le déni de service par
     * verrouillage, constat D4-01/R21-D.4). {@code failed_attempts}/{@code
     * last_failed_login_at}/{@code locked_until} (rôle : prochain instant
     * où une nouvelle tentative est comptabilisée) sont recalculés en un
     * <b>unique</b> {@code UPDATE} conditionnel, sans lecture Java
     * intermédiaire, afin d'éliminer tout risque de <i>lost update</i> sous
     * accès concurrent (même garantie et même raisonnement qu'en R21-D.3 :
     * PostgreSQL/H2 verrouillent la ligne pendant l'exécution d'un
     * {@code UPDATE} — deux exécutions concurrentes sont sérialisées par le
     * moteur, la seconde relit et recalcule à partir de l'état fraîchement
     * validé, sémantique standard READ COMMITTED).
     *
     * <p><b>Table de délai fixe</b> (décision R21-D.5, non configurable) en
     * fonction du nombre d'échecs consécutifs recalculé par cette requête :</p>
     * <pre>
     * 1-2 échecs -&gt; 0 seconde (aucun ralentissement)
     * 3 échecs   -&gt; 2 secondes
     * 4 échecs   -&gt; 5 secondes
     * 5 échecs   -&gt; 10 secondes
     * 6+ échecs  -&gt; 30 secondes (plafond, ne croît plus au-delà)
     * </pre>
     *
     * <p>Le {@code WHERE} exclut toute ligne actuellement sous ralentissement
     * actif ({@code locked_until > :now}) : dans ce cas la requête ne modifie
     * aucune ligne (0 rangée affectée) et le délai n'est <b>jamais prolongé</b>
     * par des tentatives répétées pendant qu'il est actif — la valeur de
     * retour n'a pas besoin d'être interprétée par l'appelant, la réponse
     * restant dans tous les cas identique (générique). <b>Cette méthode n'est
     * jamais appelée sur le chemin mot-de-passe-correct</b> : un mot de passe
     * correct est toujours accepté immédiatement, sans condition, quel que
     * soit l'état de ce champ (voir {@code AuthAccountService.authenticate}).</p>
     *
     * <p>La fenêtre glissante ({@code failureWindow}) et les échéances de
     * délai sont exprimées via des paramètres déjà calculés côté Java
     * ({@code now}, {@code windowStart}, {@code delayAtXxx}) — aucune
     * arithmétique de date n'est faite en SQL, ce qui garantit un
     * comportement identique sur H2 (tests) et PostgreSQL (production).</p>
     *
     * @param id            identifiant du compte
     * @param now           horodatage courant (horloge injectée du service, jamais {@code Instant.now()})
     * @param windowStart   {@code now - failureWindow} : au-delà, le compteur repart à 1
     * @param delayAtThree  {@code now + 2s} : échéance appliquée si le compte atteint 3 échecs
     * @param delayAtFour   {@code now + 5s} : échéance appliquée si le compte atteint 4 échecs
     * @param delayAtFive   {@code now + 10s} : échéance appliquée si le compte atteint 5 échecs
     * @param delayAtSixOrMore {@code now + 30s} : échéance plafond appliquée à partir de 6 échecs
     * @return le nombre de lignes affectées (0 si un ralentissement est actuellement actif, 1 sinon)
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
                               ELSE failed_attempts + 1 END) <= 2 THEN :now
                    WHEN (CASE WHEN last_failed_login_at IS NULL OR last_failed_login_at < :windowStart THEN 1
                               ELSE failed_attempts + 1 END) = 3 THEN :delayAtThree
                    WHEN (CASE WHEN last_failed_login_at IS NULL OR last_failed_login_at < :windowStart THEN 1
                               ELSE failed_attempts + 1 END) = 4 THEN :delayAtFour
                    WHEN (CASE WHEN last_failed_login_at IS NULL OR last_failed_login_at < :windowStart THEN 1
                               ELSE failed_attempts + 1 END) = 5 THEN :delayAtFive
                    ELSE :delayAtSixOrMore
                END
            WHERE id = :id
              AND (locked_until IS NULL OR locked_until <= :now)
            """, nativeQuery = true)
    int registerFailedAttempt(@Param("id") UUID id, @Param("now") Instant now, @Param("windowStart") Instant windowStart,
                               @Param("delayAtThree") Instant delayAtThree, @Param("delayAtFour") Instant delayAtFour,
                               @Param("delayAtFive") Instant delayAtFive, @Param("delayAtSixOrMore") Instant delayAtSixOrMore);

    /**
     * Remet atomiquement à zéro l'état anti-brute-force (mot de passe
     * correct) — {@code failed_attempts}/{@code last_failed_login_at}/{@code
     * locked_until} en un <b>unique</b> {@code UPDATE} scoping-minimal
     * (correction concurrence R21-D.5, remplace une mutation d'entité
     * portée par le dirty-checking Hibernate, qui aurait flush un
     * {@code UPDATE} implicite sur <b>toutes</b> les colonnes de l'entité —
     * y compris {@code email}/{@code password_hash}/{@code status}, non
     * concernées par ce reset).
     *
     * <p><b>Inconditionnelle en temps</b> (aucun {@code WHERE} sur {@code
     * locked_until}, à la différence de {@link #registerFailedAttempt}) :
     * conforme à la règle R21-D.5 selon laquelle un mot de passe correct est
     * <b>toujours</b> accepté immédiatement, y compris pendant un
     * ralentissement actif — voir {@code AuthAccountService.authenticate}.
     * Seule la valeur constante (0/NULL/NULL) est écrite, ce qui la rend
     * intrinsèquement idempotente et sans risque de <i>lost update</i> quel
     * que soit l'ordre de commit vis-à-vis d'un {@link #registerFailedAttempt}
     * concurrent.</p>
     *
     * @param id identifiant du compte
     * @return le nombre de lignes affectées (1 si le compte existe encore, 0 sinon)
     */
    @Modifying
    @Query(value = """
            UPDATE auth_account
            SET failed_attempts = 0,
                last_failed_login_at = NULL,
                locked_until = NULL
            WHERE id = :id
            """, nativeQuery = true)
    int resetFailedAttempts(@Param("id") UUID id);
}
