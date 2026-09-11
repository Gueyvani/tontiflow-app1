package com.tontiflow.interfaces.rest;

import com.tontiflow.core.dto.ErrorResponse;
import com.tontiflow.domain.model.AuthAccount;
import com.tontiflow.infrastructure.repository.AuthAccountRepository;
import com.tontiflow.infrastructure.security.jwt.JwtTestSecurityConfiguration;
import com.tontiflow.interfaces.rest.dto.LoginRequest;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prouve, au niveau HTTP, le verrouillage temporisé de compte (décision
 * R21-D.3) déclenché par {@code AuthAccountService.authenticate}.
 *
 * <p>Seuil volontairement bas ({@code account-lockout.max-failed-attempts=3})
 * via {@code @SpringBootTest properties} — l'expiration du verrouillage
 * (fenêtre/durée en minutes) n'est, elle, pas testable à ce niveau sans
 * horloge ajustable ; elle est couverte de façon déterministe par
 * {@code AuthAccountServiceTest} (horloge fixe injectée).</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"account-lockout.max-failed-attempts=3"})
@ActiveProfiles("test")
@Import(JwtTestSecurityConfiguration.class)
class AccountLockoutIntegrationTest {

    private static final String TEST_PASSWORD = "S3cur3-Test-Passw0rd!";
    private static final String WRONG_PASSWORD = "mot-de-passe-incorrect";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AuthAccountRepository authAccountRepository;

    @Test
    void login_after3FailedAttempts_locksAccountTemporarily_indiscernableFromWrongPassword() {
        String email = "lockout-http@tontiflow.test";
        register(email, TEST_PASSWORD);

        // 3 echecs : sous le seuil, chacun un simple "mauvais mot de passe" (401).
        for (int i = 0; i < 3; i++) {
            ResponseEntity<ErrorResponse> attempt = loginExpectingError(email, WRONG_PASSWORD);
            assertThat(attempt.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        // Preuve directe que le verrouillage est bien PERSISTE entre les requetes HTTP
        // (chacune sa propre transaction) - pas seulement observe via le code HTTP.
        AuthAccount persisted = authAccountRepository.findByEmail(email).orElseThrow();
        assertThat(persisted.getFailedAttempts()).isEqualTo(3);
        assertThat(persisted.getLockedUntil()).isNotNull();

        // 4e tentative, MEME AVEC LE BON MOT DE PASSE : le compte est desormais
        // temporairement verrouille. La reponse doit rester un 401 generique,
        // JAMAIS un 423 (reserve au verrouillage manuel/admin) - anti-enumeration
        // (decision R21-D.3).
        ResponseEntity<ErrorResponse> locked = loginExpectingError(email, TEST_PASSWORD);

        assertThat(locked.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(locked.getStatusCode()).isNotEqualTo(HttpStatus.LOCKED);
        assertThat(locked.getBody()).isNotNull();
        assertThat(locked.getBody().detail()).isEqualTo("Invalid credentials");
    }

    @Test
    void login_withFewerThanThresholdFailedAttempts_correctPasswordStillSucceeds() {
        String email = "lockout-http-ok@tontiflow.test";
        register(email, TEST_PASSWORD);

        // 2 echecs : reste sous le seuil (3).
        loginExpectingError(email, WRONG_PASSWORD);
        loginExpectingError(email, WRONG_PASSWORD);

        ResponseEntity<TokenResponse> response = login(email, TEST_PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().accessToken()).isNotBlank();
    }

    private ResponseEntity<Void> register(String email, String password) {
        return restTemplate.postForEntity("/api/v1/auth/register", new RegisterRequest(email, password), Void.class);
    }

    private ResponseEntity<TokenResponse> login(String email, String password) {
        return restTemplate.postForEntity("/api/v1/auth/login", new LoginRequest(email, password), TokenResponse.class);
    }

    private ResponseEntity<ErrorResponse> loginExpectingError(String email, String password) {
        return restTemplate.postForEntity("/api/v1/auth/login", new LoginRequest(email, password), ErrorResponse.class);
    }
}
