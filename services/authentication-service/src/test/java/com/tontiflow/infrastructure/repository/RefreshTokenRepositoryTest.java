package com.tontiflow.infrastructure.repository;

import com.tontiflow.domain.model.RefreshToken;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
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
