package com.tontiflow.infrastructure.repository;

import com.tontiflow.domain.enums.AccountStatus;
import com.tontiflow.domain.model.AuthAccount;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests JPA de {@link AuthAccountRepository}.
 *
 * <p>Réutilise la configuration H2 déjà définie par {@code application-test.yml}
 * ({@code @AutoConfigureTestDatabase(replace = Replace.NONE)} — pas de
 * remplacement par un datasource embarqué supplémentaire).</p>
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AuthAccountRepositoryTest {

    private static final Duration FIFTEEN_MINUTES = Duration.ofMinutes(15);

    @Autowired
    private AuthAccountRepository authAccountRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void findByEmail_withExistingEmail_returnsAccount() {
        AuthAccount saved = authAccountRepository.save(newAccount("existing@tontiflow.test"));

        Optional<AuthAccount> found = authAccountRepository.findByEmail("existing@tontiflow.test");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
    }

    @Test
    void findByEmail_withUnknownEmail_returnsEmpty() {
        Optional<AuthAccount> found = authAccountRepository.findByEmail("unknown@tontiflow.test");

        assertThat(found).isEmpty();
    }

    @Test
    void existsByEmail_withExistingEmail_returnsTrue() {
        authAccountRepository.save(newAccount("present@tontiflow.test"));

        assertThat(authAccountRepository.existsByEmail("present@tontiflow.test")).isTrue();
    }

    @Test
    void existsByEmail_withUnknownEmail_returnsFalse() {
        assertThat(authAccountRepository.existsByEmail("absent@tontiflow.test")).isFalse();
    }

    // ------------------------------------------------------------------
    // Décision R21-D.3 (correction P1, atomicité) : SQL réel (H2), séquentiel.
    // Ces tests prouvent l'arithmétique exacte de l'UPDATE atomique — la
    // preuve sous accès CONCURRENT réel est apportée séparément par
    // AccountLockoutConcurrencyIntegrationTest.
    // ------------------------------------------------------------------

    @Test
    void registerFailedAttempt_firstFailure_setsCounterToOne_andDoesNotLock() {
        UUID id = authAccountRepository.save(newAccount("ra1@tontiflow.test")).getId();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        int rows = authAccountRepository.registerFailedAttempt(id, now, now.minus(FIFTEEN_MINUTES), 5, now.plus(FIFTEEN_MINUTES));

        assertThat(rows).isEqualTo(1);
        // clear() : un UPDATE en masse (@Modifying) passe directement par JDBC et ne met
        // pas a jour le cache de premier niveau JPA - sans cela, findById(...) renverrait
        // l'entite managee PERIMEE (celle vue avant l'UPDATE) au lieu de relire la ligne
        // reellement persistee. Purement un artefact de ce test @DataJpaTest (une seule
        // transaction, un seul EntityManager) : en production, chaque requete HTTP ouvre sa
        // propre transaction/EntityManager, donc aucune staleness equivalente.
        entityManager.clear();
        AuthAccount reloaded = authAccountRepository.findById(id).orElseThrow();
        assertThat(reloaded.getFailedAttempts()).isEqualTo(1);
        assertThat(reloaded.getLastFailedLoginAt()).isEqualTo(now);
        assertThat(reloaded.getLockedUntil()).isNull();
    }

    @Test
    void registerFailedAttempt_withinWindow_incrementsExistingCounter() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        AuthAccount account = newAccount("ra2@tontiflow.test");
        account.setFailedAttempts(2);
        account.setLastFailedLoginAt(now.minus(Duration.ofMinutes(5))); // dans la fenetre de 15 min
        UUID id = authAccountRepository.save(account).getId();

        int rows = authAccountRepository.registerFailedAttempt(id, now, now.minus(FIFTEEN_MINUTES), 5, now.plus(FIFTEEN_MINUTES));

        assertThat(rows).isEqualTo(1);
        // clear() : un UPDATE en masse (@Modifying) passe directement par JDBC et ne met
        // pas a jour le cache de premier niveau JPA - sans cela, findById(...) renverrait
        // l'entite managee PERIMEE (celle vue avant l'UPDATE) au lieu de relire la ligne
        // reellement persistee. Purement un artefact de ce test @DataJpaTest (une seule
        // transaction, un seul EntityManager) : en production, chaque requete HTTP ouvre sa
        // propre transaction/EntityManager, donc aucune staleness equivalente.
        entityManager.clear();
        AuthAccount reloaded = authAccountRepository.findById(id).orElseThrow();
        assertThat(reloaded.getFailedAttempts()).isEqualTo(3);
        assertThat(reloaded.getLockedUntil()).isNull();
    }

    @Test
    void registerFailedAttempt_outsideWindow_restartsCounterAtOne() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        AuthAccount account = newAccount("ra3@tontiflow.test");
        account.setFailedAttempts(4);
        account.setLastFailedLoginAt(now.minus(Duration.ofMinutes(20))); // hors fenetre de 15 min
        UUID id = authAccountRepository.save(account).getId();

        int rows = authAccountRepository.registerFailedAttempt(id, now, now.minus(FIFTEEN_MINUTES), 5, now.plus(FIFTEEN_MINUTES));

        assertThat(rows).isEqualTo(1);
        // clear() : un UPDATE en masse (@Modifying) passe directement par JDBC et ne met
        // pas a jour le cache de premier niveau JPA - sans cela, findById(...) renverrait
        // l'entite managee PERIMEE (celle vue avant l'UPDATE) au lieu de relire la ligne
        // reellement persistee. Purement un artefact de ce test @DataJpaTest (une seule
        // transaction, un seul EntityManager) : en production, chaque requete HTTP ouvre sa
        // propre transaction/EntityManager, donc aucune staleness equivalente.
        entityManager.clear();
        AuthAccount reloaded = authAccountRepository.findById(id).orElseThrow();
        assertThat(reloaded.getFailedAttempts()).isEqualTo(1); // redemarre, pas 5
        assertThat(reloaded.getLockedUntil()).isNull();
    }

    @Test
    void registerFailedAttempt_reachingThreshold_setsLockedUntil() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        AuthAccount account = newAccount("ra4@tontiflow.test");
        account.setFailedAttempts(4);
        account.setLastFailedLoginAt(now.minus(Duration.ofMinutes(5)));
        UUID id = authAccountRepository.save(account).getId();
        Instant lockUntil = now.plus(FIFTEEN_MINUTES);

        int rows = authAccountRepository.registerFailedAttempt(id, now, now.minus(FIFTEEN_MINUTES), 5, lockUntil);

        assertThat(rows).isEqualTo(1);
        // clear() : un UPDATE en masse (@Modifying) passe directement par JDBC et ne met
        // pas a jour le cache de premier niveau JPA - sans cela, findById(...) renverrait
        // l'entite managee PERIMEE (celle vue avant l'UPDATE) au lieu de relire la ligne
        // reellement persistee. Purement un artefact de ce test @DataJpaTest (une seule
        // transaction, un seul EntityManager) : en production, chaque requete HTTP ouvre sa
        // propre transaction/EntityManager, donc aucune staleness equivalente.
        entityManager.clear();
        AuthAccount reloaded = authAccountRepository.findById(id).orElseThrow();
        assertThat(reloaded.getFailedAttempts()).isEqualTo(5);
        assertThat(reloaded.getLockedUntil()).isEqualTo(lockUntil);
    }

    @Test
    void registerFailedAttempt_whenAlreadyLocked_isNoOp_andDoesNotExtendLock() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant existingLockUntil = now.plus(Duration.ofMinutes(10));
        AuthAccount account = newAccount("ra5@tontiflow.test");
        account.setFailedAttempts(5);
        account.setLastFailedLoginAt(now.minus(Duration.ofMinutes(2)));
        account.setLockedUntil(existingLockUntil);
        UUID id = authAccountRepository.save(account).getId();

        int rows = authAccountRepository.registerFailedAttempt(id, now, now.minus(FIFTEEN_MINUTES), 5, now.plus(FIFTEEN_MINUTES));

        assertThat(rows).isEqualTo(0); // aucune ligne affectee : WHERE non satisfaite (deja verrouille)
        // clear() : un UPDATE en masse (@Modifying) passe directement par JDBC et ne met
        // pas a jour le cache de premier niveau JPA - sans cela, findById(...) renverrait
        // l'entite managee PERIMEE (celle vue avant l'UPDATE) au lieu de relire la ligne
        // reellement persistee. Purement un artefact de ce test @DataJpaTest (une seule
        // transaction, un seul EntityManager) : en production, chaque requete HTTP ouvre sa
        // propre transaction/EntityManager, donc aucune staleness equivalente.
        entityManager.clear();
        AuthAccount reloaded = authAccountRepository.findById(id).orElseThrow();
        assertThat(reloaded.getFailedAttempts()).isEqualTo(5); // inchange
        assertThat(reloaded.getLockedUntil()).isEqualTo(existingLockUntil); // jamais prolonge
    }

    @Test
    void resetFailedAttemptsIfNotLocked_whenNotLocked_resetsAllFields() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        AuthAccount account = newAccount("rr1@tontiflow.test");
        account.setFailedAttempts(3);
        account.setLastFailedLoginAt(now.minus(Duration.ofMinutes(1)));
        UUID id = authAccountRepository.save(account).getId();

        int rows = authAccountRepository.resetFailedAttemptsIfNotLocked(id, now);

        assertThat(rows).isEqualTo(1);
        // clear() : un UPDATE en masse (@Modifying) passe directement par JDBC et ne met
        // pas a jour le cache de premier niveau JPA - sans cela, findById(...) renverrait
        // l'entite managee PERIMEE (celle vue avant l'UPDATE) au lieu de relire la ligne
        // reellement persistee. Purement un artefact de ce test @DataJpaTest (une seule
        // transaction, un seul EntityManager) : en production, chaque requete HTTP ouvre sa
        // propre transaction/EntityManager, donc aucune staleness equivalente.
        entityManager.clear();
        AuthAccount reloaded = authAccountRepository.findById(id).orElseThrow();
        assertThat(reloaded.getFailedAttempts()).isZero();
        assertThat(reloaded.getLastFailedLoginAt()).isNull();
        assertThat(reloaded.getLockedUntil()).isNull();
    }

    @Test
    void resetFailedAttemptsIfNotLocked_whenCurrentlyLocked_isNoOp() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant lockedUntil = now.plus(Duration.ofMinutes(10));
        AuthAccount account = newAccount("rr2@tontiflow.test");
        account.setFailedAttempts(5);
        account.setLockedUntil(lockedUntil);
        UUID id = authAccountRepository.save(account).getId();

        int rows = authAccountRepository.resetFailedAttemptsIfNotLocked(id, now);

        assertThat(rows).isEqualTo(0);
        // clear() : un UPDATE en masse (@Modifying) passe directement par JDBC et ne met
        // pas a jour le cache de premier niveau JPA - sans cela, findById(...) renverrait
        // l'entite managee PERIMEE (celle vue avant l'UPDATE) au lieu de relire la ligne
        // reellement persistee. Purement un artefact de ce test @DataJpaTest (une seule
        // transaction, un seul EntityManager) : en production, chaque requete HTTP ouvre sa
        // propre transaction/EntityManager, donc aucune staleness equivalente.
        entityManager.clear();
        AuthAccount reloaded = authAccountRepository.findById(id).orElseThrow();
        assertThat(reloaded.getFailedAttempts()).isEqualTo(5); // inchange, pas reinitialise
        assertThat(reloaded.getLockedUntil()).isEqualTo(lockedUntil);
    }

    @Test
    void resetFailedAttemptsIfNotLocked_whenLockAlreadyExpired_resetsFields() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant expiredLockUntil = now.minus(Duration.ofSeconds(1));
        AuthAccount account = newAccount("rr3@tontiflow.test");
        account.setFailedAttempts(5);
        account.setLockedUntil(expiredLockUntil);
        UUID id = authAccountRepository.save(account).getId();

        int rows = authAccountRepository.resetFailedAttemptsIfNotLocked(id, now);

        assertThat(rows).isEqualTo(1); // expire => plus considere comme verrouille
        // clear() : un UPDATE en masse (@Modifying) passe directement par JDBC et ne met
        // pas a jour le cache de premier niveau JPA - sans cela, findById(...) renverrait
        // l'entite managee PERIMEE (celle vue avant l'UPDATE) au lieu de relire la ligne
        // reellement persistee. Purement un artefact de ce test @DataJpaTest (une seule
        // transaction, un seul EntityManager) : en production, chaque requete HTTP ouvre sa
        // propre transaction/EntityManager, donc aucune staleness equivalente.
        entityManager.clear();
        AuthAccount reloaded = authAccountRepository.findById(id).orElseThrow();
        assertThat(reloaded.getFailedAttempts()).isZero();
        assertThat(reloaded.getLockedUntil()).isNull();
    }

    private static AuthAccount newAccount(String email) {
        AuthAccount account = new AuthAccount();
        account.setEmail(email);
        // Valeur de test non sensible : ce n'est pas un veritable hash BCrypt,
        // seule la colonne NOT NULL doit etre satisfaite pour ce test JPA.
        account.setPasswordHash("test-only-not-a-real-hash");
        account.setStatus(AccountStatus.ACTIVE);
        return account;
    }
}
