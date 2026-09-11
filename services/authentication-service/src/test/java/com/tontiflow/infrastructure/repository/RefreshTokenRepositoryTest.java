package com.tontiflow.infrastructure.repository;

import com.tontiflow.domain.model.RefreshToken;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Tests JPA de {@link RefreshTokenRepository}.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RefreshTokenRepositoryTest {

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void findByTokenHash_withExistingHash_returnsToken() {
        RefreshToken saved = refreshTokenRepository.save(newToken("hash-existing", UUID.randomUUID()));

        Optional<RefreshToken> found = refreshTokenRepository.findByTokenHash("hash-existing");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
    }

    @Test
    void findByTokenHash_withUnknownHash_returnsEmpty() {
        Optional<RefreshToken> found = refreshTokenRepository.findByTokenHash("hash-unknown");

        assertThat(found).isEmpty();
    }

    @Test
    @Transactional
    void revokeFamily_revokesOnlyActiveTokensOfThatFamily() {
        UUID targetFamily = UUID.randomUUID();
        UUID otherFamily = UUID.randomUUID();

        RefreshToken activeInTargetFamily = refreshTokenRepository.save(newToken("hash-active-target", targetFamily));

        RefreshToken alreadyRevoked = newToken("hash-already-revoked", targetFamily);
        Instant originalRevocation = Instant.parse("2026-01-01T00:00:00Z");
        alreadyRevoked.setRevokedAt(originalRevocation);
        alreadyRevoked = refreshTokenRepository.save(alreadyRevoked);

        RefreshToken activeInOtherFamily = refreshTokenRepository.save(newToken("hash-active-other", otherFamily));

        Instant revocationTime = Instant.now();
        refreshTokenRepository.revokeFamily(targetFamily, revocationTime);
        refreshTokenRepository.flush();

        RefreshToken reloadedActive = refreshTokenRepository.findById(activeInTargetFamily.getId()).orElseThrow();
        RefreshToken reloadedAlreadyRevoked = refreshTokenRepository.findById(alreadyRevoked.getId()).orElseThrow();
        RefreshToken reloadedOtherFamily = refreshTokenRepository.findById(activeInOtherFamily.getId()).orElseThrow();

        // Le token actif de la famille ciblee est revoque.
        assertThat(reloadedActive.getRevokedAt()).isCloseTo(revocationTime, within(1, ChronoUnit.SECONDS));
        // Le token deja revoque conserve sa date de revocation d'origine (non ecrasee).
        assertThat(reloadedAlreadyRevoked.getRevokedAt()).isEqualTo(originalRevocation);
        // Le token d'une autre famille n'est pas touche.
        assertThat(reloadedOtherFamily.getRevokedAt()).isNull();
    }

    // ------------------------------------------------------------------
    // Décision R21-D.6 (correction concurrence, constat D4-02/R21-D.4) : SQL
    // réel (H2), séquentiel. La preuve sous accès CONCURRENT réel est
    // apportée séparément par RefreshTokenConcurrencyIntegrationTest.
    // ------------------------------------------------------------------

    @Test
    void consumeIfActive_success() {
        RefreshToken saved = refreshTokenRepository.save(newToken("hash-consume-1", UUID.randomUUID()));
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        int rows = refreshTokenRepository.consumeIfActive(saved.getId(), now);

        assertThat(rows).isEqualTo(1);
        // clear() : un UPDATE en masse (@Modifying) passe directement par JDBC et ne met
        // pas a jour le cache de premier niveau JPA - sans cela, findById(...) renverrait
        // l'entite managee PERIMEE (celle vue avant l'UPDATE) au lieu de relire la ligne
        // reellement persistee. Purement un artefact de ce test @DataJpaTest (une seule
        // transaction, un seul EntityManager) : en production, chaque requete HTTP ouvre sa
        // propre transaction/EntityManager, donc aucune staleness equivalente.
        entityManager.clear();
        RefreshToken reloaded = refreshTokenRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getRevokedAt()).isEqualTo(now);
    }

    @Test
    void consumeIfActive_returnsZeroWhenAlreadyConsumed() {
        RefreshToken token = newToken("hash-consume-2", UUID.randomUUID());
        Instant originalRevocation = Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(60);
        token.setRevokedAt(originalRevocation);
        RefreshToken saved = refreshTokenRepository.save(token);

        int rows = refreshTokenRepository.consumeIfActive(saved.getId(), Instant.now().truncatedTo(ChronoUnit.MILLIS));

        assertThat(rows).isEqualTo(0); // aucune ligne affectee : WHERE non satisfaite (deja consomme)
        entityManager.clear();
        RefreshToken reloaded = refreshTokenRepository.findById(saved.getId()).orElseThrow();
        // La date de revocation d'origine n'est jamais ecrasee par un appel ulterieur.
        assertThat(reloaded.getRevokedAt()).isEqualTo(originalRevocation);
    }

    private static RefreshToken newToken(String tokenHash, UUID familyId) {
        RefreshToken token = new RefreshToken();
        token.setAccountId(UUID.randomUUID());
        token.setTokenHash(tokenHash);
        token.setFamilyId(familyId);
        token.setIssuedAt(Instant.now());
        token.setExpiresAt(Instant.now().plusSeconds(3600));
        return token;
    }
}
