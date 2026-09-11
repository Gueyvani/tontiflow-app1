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
    // Décision R21-D.5 (ralentissement progressif, remplace le verrouillage
    // dur de R21-D.3 - corrige D4-01/R21-D.4) : SQL réel (H2), séquentiel.
    // Ces tests prouvent la table de délai exacte de l'UPDATE atomique -
    // 1-2 échecs -> 0s, 3 -> 2s, 4 -> 5s, 5 -> 10s, 6+ -> 30s (plafond).
    // La preuve sous accès CONCURRENT réel est apportée séparément par
    // AccountLockoutConcurrencyIntegrationTest.
    // ------------------------------------------------------------------

    @Test
    void registerFailedAttempt_firstFailure_setsNoDelay() {
        UUID id = authAccountRepository.save(newAccount("ra1@tontiflow.test")).getId();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        int rows = registerFailedAttempt(id, now);

        assertThat(rows).isEqualTo(1);
        AuthAccount reloaded = reload(id);
        assertThat(reloaded.getFailedAttempts()).isEqualTo(1);
        assertThat(reloaded.getLastFailedLoginAt()).isEqualTo(now);
        assertThat(reloaded.getNextAttemptAllowedAt()).isEqualTo(now); // 1 echec -> aucun delai
    }

    @Test
    void registerFailedAttempt_secondFailure_stillSetsNoDelay() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        AuthAccount account = newAccount("ra2@tontiflow.test");
        account.setFailedAttempts(1);
        account.setLastFailedLoginAt(now.minus(Duration.ofMinutes(1))); // dans la fenetre de 15 min
        UUID id = authAccountRepository.save(account).getId();

        int rows = registerFailedAttempt(id, now);

        assertThat(rows).isEqualTo(1);
        AuthAccount reloaded = reload(id);
        assertThat(reloaded.getFailedAttempts()).isEqualTo(2);
        assertThat(reloaded.getNextAttemptAllowedAt()).isEqualTo(now); // 2 echecs -> toujours aucun delai
    }

    @Test
    void registerFailedAttempt_thirdFailure_setsTwoSecondDelay() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        AuthAccount account = newAccount("ra3@tontiflow.test");
        account.setFailedAttempts(2);
        account.setLastFailedLoginAt(now.minus(Duration.ofMinutes(1)));
        UUID id = authAccountRepository.save(account).getId();

        int rows = registerFailedAttempt(id, now);

        assertThat(rows).isEqualTo(1);
        AuthAccount reloaded = reload(id);
        assertThat(reloaded.getFailedAttempts()).isEqualTo(3);
        assertThat(reloaded.getNextAttemptAllowedAt()).isEqualTo(now.plusSeconds(2));
    }

    @Test
    void registerFailedAttempt_fourthFailure_setsFiveSecondDelay() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        AuthAccount account = newAccount("ra4@tontiflow.test");
        account.setFailedAttempts(3);
        account.setLastFailedLoginAt(now.minus(Duration.ofMinutes(1)));
        UUID id = authAccountRepository.save(account).getId();

        int rows = registerFailedAttempt(id, now);

        assertThat(rows).isEqualTo(1);
        AuthAccount reloaded = reload(id);
        assertThat(reloaded.getFailedAttempts()).isEqualTo(4);
        assertThat(reloaded.getNextAttemptAllowedAt()).isEqualTo(now.plusSeconds(5));
    }

    @Test
    void registerFailedAttempt_fifthFailure_setsTenSecondDelay() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        AuthAccount account = newAccount("ra5@tontiflow.test");
        account.setFailedAttempts(4);
        account.setLastFailedLoginAt(now.minus(Duration.ofMinutes(1)));
        UUID id = authAccountRepository.save(account).getId();

        int rows = registerFailedAttempt(id, now);

        assertThat(rows).isEqualTo(1);
        AuthAccount reloaded = reload(id);
        assertThat(reloaded.getFailedAttempts()).isEqualTo(5);
        assertThat(reloaded.getNextAttemptAllowedAt()).isEqualTo(now.plusSeconds(10));
    }

    @Test
    void registerFailedAttempt_sixthFailure_capsAtThirtySecondDelay() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        AuthAccount account = newAccount("ra6@tontiflow.test");
        account.setFailedAttempts(5);
        account.setLastFailedLoginAt(now.minus(Duration.ofMinutes(1)));
        UUID id = authAccountRepository.save(account).getId();

        int rows = registerFailedAttempt(id, now);

        assertThat(rows).isEqualTo(1);
        AuthAccount reloaded = reload(id);
        assertThat(reloaded.getFailedAttempts()).isEqualTo(6);
        assertThat(reloaded.getNextAttemptAllowedAt()).isEqualTo(now.plusSeconds(30));
    }

    @Test
    void registerFailedAttempt_wellBeyondSixFailures_staysCappedAtThirtySeconds() {
        // Prouve que le plafond ne croit jamais au-dela de 30s, meme apres de nombreux
        // echecs supplementaires (decision R21-D.5 : "ne pas utiliser de delai
        // exponentiel non borne").
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        AuthAccount account = newAccount("ra7@tontiflow.test");
        account.setFailedAttempts(9);
        account.setLastFailedLoginAt(now.minus(Duration.ofMinutes(1)));
        UUID id = authAccountRepository.save(account).getId();

        int rows = registerFailedAttempt(id, now);

        assertThat(rows).isEqualTo(1);
        AuthAccount reloaded = reload(id);
        assertThat(reloaded.getFailedAttempts()).isEqualTo(10);
        assertThat(reloaded.getNextAttemptAllowedAt()).isEqualTo(now.plusSeconds(30)); // toujours 30s, pas plus
    }

    @Test
    void registerFailedAttempt_outsideWindow_restartsAtOne_noDelay() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        AuthAccount account = newAccount("ra8@tontiflow.test");
        account.setFailedAttempts(5);
        account.setLastFailedLoginAt(now.minus(Duration.ofMinutes(20))); // hors fenetre de 15 min
        UUID id = authAccountRepository.save(account).getId();

        int rows = registerFailedAttempt(id, now);

        assertThat(rows).isEqualTo(1);
        AuthAccount reloaded = reload(id);
        assertThat(reloaded.getFailedAttempts()).isEqualTo(1); // redemarre, pas 6
        assertThat(reloaded.getNextAttemptAllowedAt()).isEqualTo(now); // aucun delai
    }

    @Test
    void registerFailedAttempt_whenWithinActiveDelay_isNoOp_andDoesNotExtendDelay() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant existingDelay = now.plus(Duration.ofSeconds(20)); // delai en cours, dans le futur
        AuthAccount account = newAccount("ra9@tontiflow.test");
        account.setFailedAttempts(6);
        account.setLastFailedLoginAt(now.minus(Duration.ofMinutes(2)));
        account.setNextAttemptAllowedAt(existingDelay);
        UUID id = authAccountRepository.save(account).getId();

        int rows = registerFailedAttempt(id, now);

        assertThat(rows).isEqualTo(0); // aucune ligne affectee : WHERE non satisfaite (delai actif)
        AuthAccount reloaded = reload(id);
        assertThat(reloaded.getFailedAttempts()).isEqualTo(6); // inchange
        assertThat(reloaded.getNextAttemptAllowedAt()).isEqualTo(existingDelay); // jamais prolonge
    }

    private int registerFailedAttempt(UUID id, Instant now) {
        return authAccountRepository.registerFailedAttempt(id, now, now.minus(FIFTEEN_MINUTES),
                now.plusSeconds(2), now.plusSeconds(5), now.plusSeconds(10), now.plusSeconds(30));
    }

    /**
     * clear() : un UPDATE en masse ({@code @Modifying}) passe directement par JDBC et ne
     * met pas à jour le cache de premier niveau JPA — sans cela, {@code findById(...)}
     * renverrait l'entité managée PÉRIMÉE (celle vue avant l'UPDATE) au lieu de relire la
     * ligne réellement persistée. Purement un artefact de ce test {@code @DataJpaTest}
     * (une seule transaction, un seul {@code EntityManager}) : en production, chaque
     * requête HTTP ouvre sa propre transaction/EntityManager, donc aucune staleness
     * équivalente.
     */
    private AuthAccount reload(UUID id) {
        entityManager.clear();
        return authAccountRepository.findById(id).orElseThrow();
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
