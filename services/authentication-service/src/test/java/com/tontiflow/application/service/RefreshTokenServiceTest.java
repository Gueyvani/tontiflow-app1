package com.tontiflow.application.service;

import com.tontiflow.application.exception.InvalidRefreshTokenException;
import com.tontiflow.application.exception.RefreshTokenExpiredException;
import com.tontiflow.application.exception.RefreshTokenReuseDetectedException;
import com.tontiflow.domain.model.RefreshToken;
import com.tontiflow.infrastructure.repository.RefreshTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires de {@link RefreshTokenService}.
 *
 * <p>Utilise une {@link Clock} fixe pour rendre déterministes les durées de
 * vie et les horodatages de révocation.</p>
 */
@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    private RefreshTokenService refreshTokenService;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        refreshTokenService = new RefreshTokenService(refreshTokenRepository, fixedClock, "30d");
        // lenient() : ce stub n'est exerce que par les scenarios qui atteignent vraiment
        // la persistance (issue, rotate valide) ; les scenarios d'echec (token inconnu/
        // expire/deja revoque) s'arretent avant, ce qui rendrait le stub "inutile" en mode strict.
        lenient().when(refreshTokenRepository.save(any(RefreshToken.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void issue_createsTokenWithHashedValueAndNewFamily() {
        UUID accountId = UUID.randomUUID();

        RefreshToken issued = refreshTokenService.issue(accountId);

        assertThat(issued.getAccountId()).isEqualTo(accountId);
        assertThat(issued.getRawToken()).isNotBlank();
        assertThat(issued.getTokenHash()).isNotBlank().isNotEqualTo(issued.getRawToken());
        assertThat(issued.getFamilyId()).isNotNull();
        assertThat(issued.getIssuedAt()).isEqualTo(FIXED_NOW);
        assertThat(issued.getExpiresAt()).isEqualTo(FIXED_NOW.plus(Duration.ofDays(30)));
        assertThat(issued.getRevokedAt()).isNull();
    }

    @Test
    void rotate_withValidToken_revokesOldAndIssuesNewInSameFamily() {
        UUID accountId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();
        RefreshToken existing = activeToken(accountId, familyId, "existing-hash", FIXED_NOW.plusSeconds(3600));

        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(existing));

        RefreshToken rotated = refreshTokenService.rotate("raw-token-value");

        assertThat(existing.getRevokedAt()).isEqualTo(FIXED_NOW);
        assertThat(rotated.getFamilyId()).isEqualTo(familyId);
        assertThat(rotated.getAccountId()).isEqualTo(accountId);
        assertThat(rotated.getRawToken()).isNotBlank();
        assertThat(rotated.getTokenHash()).isNotEqualTo(existing.getTokenHash());
    }

    @Test
    void rotate_withUnknownToken_throwsInvalidRefreshTokenException() {
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> refreshTokenService.rotate("unknown-token"))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void rotate_withExpiredToken_throwsRefreshTokenExpiredException() {
        RefreshToken expired = activeToken(UUID.randomUUID(), UUID.randomUUID(), "expired-hash",
                FIXED_NOW.minusSeconds(1));
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> refreshTokenService.rotate("expired-token"))
                .isInstanceOf(RefreshTokenExpiredException.class);
    }

    @Test
    void rotate_withAlreadyRevokedToken_throwsReuseDetectedAndRevokesFamily() {
        UUID familyId = UUID.randomUUID();
        RefreshToken revoked = activeToken(UUID.randomUUID(), familyId, "revoked-hash", FIXED_NOW.plusSeconds(3600));
        revoked.setRevokedAt(FIXED_NOW.minusSeconds(60));
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(revoked));

        assertThatThrownBy(() -> refreshTokenService.rotate("reused-token"))
                .isInstanceOf(RefreshTokenReuseDetectedException.class);

        verify(refreshTokenRepository).revokeFamily(familyId, FIXED_NOW);
    }

    // ------------------------------------------------------------------
    // revoke (logout) — décision Q5, audit Phase Q
    // ------------------------------------------------------------------

    @Test
    void revoke_withValidToken_revokesItsFamily() {
        UUID familyId = UUID.randomUUID();
        RefreshToken existing = activeToken(UUID.randomUUID(), familyId, "existing-hash", FIXED_NOW.plusSeconds(3600));
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(existing));

        refreshTokenService.revoke("raw-token-value");

        verify(refreshTokenRepository).revokeFamily(familyId, FIXED_NOW);
    }

    @Test
    void revoke_withUnknownToken_throwsInvalidRefreshTokenException() {
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> refreshTokenService.revoke("unknown-token"))
                .isInstanceOf(InvalidRefreshTokenException.class);

        verify(refreshTokenRepository, org.mockito.Mockito.never()).revokeFamily(any(), any());
    }

    @Test
    void revoke_withAlreadyExpiredToken_stillRevokesFamily() {
        // A la difference de rotate(), revoke() ne verifie pas l'expiration :
        // se deconnecter d'une session dont le token presente est perime doit
        // tout de meme invalider le reste de la famille.
        UUID familyId = UUID.randomUUID();
        RefreshToken expired = activeToken(UUID.randomUUID(), familyId, "expired-hash", FIXED_NOW.minusSeconds(1));
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(expired));

        refreshTokenService.revoke("expired-token");

        verify(refreshTokenRepository).revokeFamily(familyId, FIXED_NOW);
    }

    @Test
    void revoke_withAlreadyRevokedToken_isIdempotent_doesNotThrow() {
        // Deuxieme logout avec le meme token : ne doit ni lever d'exception,
        // ni reactiver quoi que ce soit (revokeFamily ne touche que les lignes
        // encore actives - propriete deja garantie par la requete SQL elle-meme).
        UUID familyId = UUID.randomUUID();
        RefreshToken revoked = activeToken(UUID.randomUUID(), familyId, "revoked-hash", FIXED_NOW.plusSeconds(3600));
        revoked.setRevokedAt(FIXED_NOW.minusSeconds(60));
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(revoked));

        org.assertj.core.api.Assertions.assertThatCode(() -> refreshTokenService.revoke("already-revoked-token"))
                .doesNotThrowAnyException();

        verify(refreshTokenRepository).revokeFamily(familyId, FIXED_NOW);
    }

    private static RefreshToken activeToken(UUID accountId, UUID familyId, String tokenHash, Instant expiresAt) {
        RefreshToken token = new RefreshToken();
        token.setAccountId(accountId);
        token.setFamilyId(familyId);
        token.setTokenHash(tokenHash);
        token.setIssuedAt(FIXED_NOW.minusSeconds(120));
        token.setExpiresAt(expiresAt);
        return token;
    }
}
