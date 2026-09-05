package com.tontiflow.interfaces.rest;

import com.tontiflow.UserContext;
import com.tontiflow.core.dto.ErrorResponse;
import com.tontiflow.domain.model.AuthAccount;
import com.tontiflow.domain.model.RefreshToken;
import com.tontiflow.infrastructure.repository.AuthAccountRepository;
import com.tontiflow.infrastructure.repository.RefreshTokenRepository;
import com.tontiflow.infrastructure.security.jwt.AccessTokenService;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test d'intégration du mécanisme Refresh Token : émission au login,
 * rotation à usage unique, détection de réutilisation et révocation de
 * famille.
 *
 * <p>Même pattern que {@code AuthControllerIntegrationTest} : paire de clés
 * RSA éphémère ({@link JwtTestSecurityConfiguration}), aucune clé réelle.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(JwtTestSecurityConfiguration.class)
class RefreshTokenIntegrationTest {

    private static final String TEST_PASSWORD = "S3cur3-Test-Passw0rd!";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AuthAccountRepository authAccountRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private AccessTokenService accessTokenService;

    @Test
    void login_returnsNonBlankRefreshToken() {
        TokenResponse response = login("refresh-login@tontiflow.test");

        assertThat(response.refreshToken()).isNotBlank();
    }

    @Test
    void refresh_withValidToken_returns200WithNewTokens() {
        TokenResponse loginResponse = login("refresh-valid@tontiflow.test");

        ResponseEntity<TokenResponse> refreshResponse = doRefresh(loginResponse.refreshToken());

        assertThat(refreshResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        TokenResponse body = refreshResponse.getBody();
        assertThat(body.accessToken()).isNotBlank();
        assertThat(body.refreshToken()).isNotBlank().isNotEqualTo(loginResponse.refreshToken());
    }

    @Test
    void refresh_newAccessTokenIsValidAndReflectsUserContext() {
        TokenResponse loginResponse = login("refresh-context@tontiflow.test");

        TokenResponse refreshed = doRefresh(loginResponse.refreshToken()).getBody();
        UserContext context = accessTokenService.validate(refreshed.accessToken());

        assertThat(context.username()).isEqualTo("refresh-context@tontiflow.test");
        assertThat(context.email()).isEqualTo("refresh-context@tontiflow.test");
    }

    @Test
    void refresh_reusingOldToken_returns401() {
        TokenResponse loginResponse = login("refresh-reuse@tontiflow.test");
        String originalRefreshToken = loginResponse.refreshToken();

        // Premiere rotation : consomme (revoque) le token original.
        doRefresh(originalRefreshToken);

        // Reutilisation du token original, deja tourne : doit etre rejetee.
        ResponseEntity<ErrorResponse> reuseResponse = restTemplate.postForEntity(
                "/api/v1/auth/refresh", new RefreshTokenRequest(originalRefreshToken), ErrorResponse.class);

        assertThat(reuseResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refresh_reuseAlsoRevokesNextGenerationToken() {
        TokenResponse loginResponse = login("refresh-family-revoke@tontiflow.test");
        String originalRefreshToken = loginResponse.refreshToken();

        // Premiere rotation : produit un token de "generation suivante" dans la meme famille.
        TokenResponse nextGeneration = doRefresh(originalRefreshToken).getBody();

        // Reutilisation du token original -> detection, revocation de TOUTE la famille.
        restTemplate.postForEntity(
                "/api/v1/auth/refresh", new RefreshTokenRequest(originalRefreshToken), ErrorResponse.class);

        // Le token de generation suivante, pourtant jamais reutilise lui-meme,
        // doit desormais etre inutilisable : preuve que toute la famille a ete revoquee.
        ResponseEntity<ErrorResponse> response = restTemplate.postForEntity(
                "/api/v1/auth/refresh", new RefreshTokenRequest(nextGeneration.refreshToken()), ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refresh_withUnknownToken_returns401() {
        ResponseEntity<ErrorResponse> response = restTemplate.postForEntity(
                "/api/v1/auth/refresh", new RefreshTokenRequest("ceci-n-est-pas-un-token-connu"), ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refresh_withExpiredToken_returns401() {
        String email = "refresh-expired@tontiflow.test";
        TokenResponse loginResponse = login(email);
        AuthAccount account = authAccountRepository.findByEmail(email).orElseThrow();

        RefreshToken persisted = refreshTokenRepository.findAll().stream()
                .filter(token -> token.getAccountId().equals(account.getId()) && token.getRevokedAt() == null)
                .findFirst()
                .orElseThrow();
        persisted.setExpiresAt(Instant.now().minusSeconds(1));
        refreshTokenRepository.save(persisted);

        ResponseEntity<ErrorResponse> response = restTemplate.postForEntity(
                "/api/v1/auth/refresh", new RefreshTokenRequest(loginResponse.refreshToken()), ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refresh_withoutJwt_isAccessible() {
        TokenResponse loginResponse = login("refresh-no-jwt@tontiflow.test");

        ResponseEntity<TokenResponse> response = doRefresh(loginResponse.refreshToken());

        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ------------------------------------------------------------------
    // logout — décision Q5, audit Phase Q. Vrai HTTP + vraie DB (H2), même
    // patron que les tests /refresh ci-dessus.
    // ------------------------------------------------------------------

    // TEST Q5.1 : logout avec refresh token valide -> famille revoquee.
    @Test
    void logout_withValidToken_returns200() {
        TokenResponse loginResponse = login("logout-valid@tontiflow.test");

        ResponseEntity<Void> response = doLogout(loginResponse.refreshToken());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // TEST Q5.2 : tentative de refresh apres logout -> rejete (famille revoquee).
    @Test
    void logout_thenRefreshWithSameToken_returns401() {
        TokenResponse loginResponse = login("logout-then-refresh@tontiflow.test");
        String refreshToken = loginResponse.refreshToken();

        doLogout(refreshToken);

        ResponseEntity<ErrorResponse> response = restTemplate.postForEntity(
                "/api/v1/auth/refresh", new RefreshTokenRequest(refreshToken), ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // TEST Q5.3 : logout deux fois avec le meme token -> reste sur, aucune
    // exception, aucune reactivation.
    @Test
    void logout_calledTwiceWithSameToken_remainsSafeAndIdempotent() {
        TokenResponse loginResponse = login("logout-twice@tontiflow.test");
        String refreshToken = loginResponse.refreshToken();

        ResponseEntity<Void> first = doLogout(refreshToken);
        ResponseEntity<Void> second = doLogout(refreshToken);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Le token reste bien revoque (pas reactive) : un refresh reste rejete.
        ResponseEntity<ErrorResponse> refreshAfter = restTemplate.postForEntity(
                "/api/v1/auth/refresh", new RefreshTokenRequest(refreshToken), ErrorResponse.class);
        assertThat(refreshAfter.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // TEST Q5.4 : le logout d'un compte ne doit jamais affecter la famille
    // d'un autre compte (le familyId n'est jamais fourni par le client -
    // toujours derive du hash du token presente).
    @Test
    void logout_forOneAccount_doesNotAffectAnotherAccountsToken() {
        TokenResponse accountA = login("logout-isolation-a@tontiflow.test");
        TokenResponse accountB = login("logout-isolation-b@tontiflow.test");

        doLogout(accountA.refreshToken());

        // Le refresh token du compte B reste pleinement valide.
        ResponseEntity<TokenResponse> refreshB = doRefresh(accountB.refreshToken());
        assertThat(refreshB.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // TEST Q5.5 : token inexistant/invalide -> reponse controlee (401),
    // meme message generique que /refresh, aucune fuite d'information.
    @Test
    void logout_withUnknownToken_returns401() {
        ResponseEntity<ErrorResponse> response = restTemplate.postForEntity(
                "/api/v1/auth/logout", new RefreshTokenRequest("ceci-n-est-pas-un-token-connu"), ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void logout_withoutJwt_isAccessible() {
        TokenResponse loginResponse = login("logout-no-jwt@tontiflow.test");

        ResponseEntity<Void> response = doLogout(loginResponse.refreshToken());

        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private ResponseEntity<Void> doLogout(String refreshToken) {
        return restTemplate.postForEntity(
                "/api/v1/auth/logout", new RefreshTokenRequest(refreshToken), Void.class);
    }

    private TokenResponse login(String email) {
        restTemplate.postForEntity("/api/v1/auth/register", new RegisterRequest(email, TEST_PASSWORD), Void.class);
        ResponseEntity<TokenResponse> response = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), TokenResponse.class);
        return response.getBody();
    }

    private ResponseEntity<TokenResponse> doRefresh(String refreshToken) {
        return restTemplate.postForEntity(
                "/api/v1/auth/refresh", new RefreshTokenRequest(refreshToken), TokenResponse.class);
    }
}
