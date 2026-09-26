package com.tontiflow.interfaces.rest;

import com.tontiflow.application.service.RefreshTokenPurgeService;
import com.tontiflow.domain.model.AuthAccount;
import com.tontiflow.domain.model.RefreshToken;
import com.tontiflow.infrastructure.repository.AuthAccountRepository;
import com.tontiflow.infrastructure.repository.RefreshTokenRepository;
import com.tontiflow.infrastructure.security.jwt.JwtTestSecurityConfiguration;
import com.tontiflow.interfaces.rest.dto.LoginRequest;
import com.tontiflow.interfaces.rest.dto.RefreshTokenRequest;
import com.tontiflow.interfaces.rest.dto.RegisterRequest;
import com.tontiflow.interfaces.rest.dto.TokenResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Décision F-2 - de bout en bout (vrai HTTP, vraie base H2) : la purge physique des familles
 * entièrement expirées ne change ni le refresh, ni la détection de réutilisation, ni le logout, sauf la
 * décision D-logout (famille entièrement purgée : 401 « jeton inconnu »).
 *
 * <p>La planification est désactivée sous le profil {@code test} : les tests appellent
 * {@link RefreshTokenPurgeService} directement. Chaque scénario utilise son propre compte ; les
 * expirations sont produites en modifiant {@code expires_at} des lignes du compte (même technique que
 * {@code RefreshTokenIntegrationTest}).</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(JwtTestSecurityConfiguration.class)
class RefreshTokenPurgeIntegrationTest {

    private static final String TEST_PASSWORD = "S3cur3-Test-Passw0rd!";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AuthAccountRepository authAccountRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private RefreshTokenPurgeService purgeService;

    /**
     * Scénario obligatoire de F-2 : protège la décision centrale (une famille n'est purgée que si
     * TOUS ses jetons sont expirés ; un ancien jeton expiré d'une famille vivante reste utilisable
     * pour un logout qui révoque le successeur).
     */
    @Test
    void criticalScenario_oldExpiredTokenOfALiveFamily_isKept_thenWholeFamilyIsPurged_andLogoutBecomes401() {
        String email = "purge-critical@tontiflow.test";
        // 1-2. Famille F, jeton A.
        TokenResponse login = login(email);
        String rawA = login.refreshToken();
        UUID accountId = accountId(email);

        // 3-4. A consomme, B cree dans F.
        TokenResponse refreshed = doRefresh(rawA).getBody();
        String rawB = refreshed.refreshToken();
        RefreshToken rowA = rowsOf(accountId).stream().filter(t -> t.getRevokedAt() != null).findFirst().orElseThrow();
        RefreshToken rowB = rowsOf(accountId).stream().filter(t -> t.getRevokedAt() == null).findFirst().orElseThrow();
        assertThat(rowA.getFamilyId()).isEqualTo(rowB.getFamilyId());

        // 5-6. A expire (au-dela de la marge), B reste valide.
        expire(rowA, Instant.now().minus(1, ChronoUnit.DAYS));

        // 7-8. Purge : A reste present (famille encore vivante), B aussi.
        purgeService.purgeExpiredFamilies();
        assertThat(rowsOf(accountId)).extracting(RefreshToken::getId).contains(rowA.getId(), rowB.getId());

        // 9-10. Logout avec A (expire, famille vivante) : 200 et B est revoque.
        ResponseEntity<Void> logoutWithA = doLogout(rawA);
        assertThat(logoutWithA.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refreshTokenRepository.findById(rowB.getId()).orElseThrow().getRevokedAt()).isNotNull();
        assertThat(doRefresh(rawB).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // 11-12. B expire aussi, bien au-dela de la marge : toute la famille est expiree.
        expire(refreshTokenRepository.findById(rowB.getId()).orElseThrow(), Instant.now().minus(1, ChronoUnit.DAYS));

        // 13-14. Purge : toute la famille F est supprimee.
        RefreshTokenPurgeService.PurgeResult secondPass = purgeService.purgeExpiredFamilies();
        assertThat(secondPass.deletedRows()).isGreaterThanOrEqualTo(2);
        assertThat(rowsOf(accountId)).isEmpty();

        // 15-16. Decision D-logout : jeton d'une famille entierement purgee -> 401 "jeton inconnu".
        assertThat(doLogout(rawA).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(doLogout(rawB).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(doRefresh(rawB).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void activeToken_isNeverPurged_andStillRefreshable() {
        String email = "purge-active@tontiflow.test";
        TokenResponse login = login(email);

        purgeService.purgeExpiredFamilies();

        assertThat(rowsOf(accountId(email))).hasSize(1);
        assertThat(doRefresh(login.refreshToken()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void reuseDetection_isPreservedAcrossAPurgePass_whileTheConsumedTokenIsNotExpired() {
        String email = "purge-reuse@tontiflow.test";
        TokenResponse login = login(email);
        String rawA = login.refreshToken();
        String rawB = doRefresh(rawA).getBody().refreshToken();

        purgeService.purgeExpiredFamilies();

        // A est consomme mais non expire : son rejeu doit toujours etre detecte et revoquer la famille.
        assertThat(doRefresh(rawA).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(doRefresh(rawB).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rowsOf(accountId(email))).allMatch(t -> t.getRevokedAt() != null);
    }

    @Test
    void revokedButNotExpiredFamily_isKeptByThePurge() {
        String email = "purge-revoked-live@tontiflow.test";
        TokenResponse login = login(email);
        assertThat(doLogout(login.refreshToken()).getStatusCode()).isEqualTo(HttpStatus.OK);

        purgeService.purgeExpiredFamilies();

        List<RefreshToken> rows = rowsOf(accountId(email));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getRevokedAt()).isNotNull();
        // Rejeu du jeton revoque non expire : toujours 401 (comportement inchange).
        assertThat(doRefresh(login.refreshToken()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void expiredTokenWithinTheGraceMargin_isNotPurged() {
        String email = "purge-grace@tontiflow.test";
        login(email);
        UUID accountId = accountId(email);
        // Expire depuis 1 minute seulement : dans la marge technique (10 min par defaut).
        expire(rowsOf(accountId).get(0), Instant.now().minus(1, ChronoUnit.MINUTES));

        purgeService.purgeExpiredFamilies();

        assertThat(rowsOf(accountId)).hasSize(1);
    }

    @Test
    void repeatedPurge_isIdempotent() {
        String email = "purge-idempotent@tontiflow.test";
        login(email);
        UUID accountId = accountId(email);
        expire(rowsOf(accountId).get(0), Instant.now().minus(2, ChronoUnit.DAYS));

        purgeService.purgeExpiredFamilies();
        RefreshTokenPurgeService.PurgeResult second = purgeService.purgeExpiredFamilies();

        assertThat(rowsOf(accountId)).isEmpty();
        assertThat(second.deletedRows()).isZero();
    }

    @Test
    void logoutWithAnUnknownToken_stillReturns401_asBefore() {
        assertThat(doLogout("jeton-inconnu-de-ce-test-purge").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ------------------------------------------------------------------

    private UUID accountId(String email) {
        AuthAccount account = authAccountRepository.findByEmail(email).orElseThrow();
        return account.getId();
    }

    private List<RefreshToken> rowsOf(UUID accountId) {
        return refreshTokenRepository.findAll().stream()
                .filter(token -> token.getAccountId().equals(accountId))
                .toList();
    }

    private void expire(RefreshToken token, Instant expiresAt) {
        token.setExpiresAt(expiresAt);
        refreshTokenRepository.save(token);
    }

    private ResponseEntity<Void> doLogout(String refreshToken) {
        return restTemplate.postForEntity("/api/v1/auth/logout", new RefreshTokenRequest(refreshToken), Void.class);
    }

    private TokenResponse login(String email) {
        restTemplate.postForEntity("/api/v1/auth/register", new RegisterRequest(email, TEST_PASSWORD), Void.class);
        return restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), TokenResponse.class).getBody();
    }

    private ResponseEntity<TokenResponse> doRefresh(String refreshToken) {
        return restTemplate.postForEntity(
                "/api/v1/auth/refresh", new RefreshTokenRequest(refreshToken), TokenResponse.class);
    }
}
