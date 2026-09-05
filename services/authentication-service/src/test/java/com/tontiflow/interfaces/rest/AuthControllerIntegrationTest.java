package com.tontiflow.interfaces.rest;

import com.tontiflow.UserContext;
import com.tontiflow.core.dto.ErrorResponse;
import com.tontiflow.domain.enums.AccountStatus;
import com.tontiflow.domain.model.AuthAccount;
import com.tontiflow.infrastructure.repository.AuthAccountRepository;
import com.tontiflow.infrastructure.security.jwt.AccessTokenService;
import com.tontiflow.infrastructure.security.jwt.JwtTestSecurityConfiguration;
import com.tontiflow.interfaces.rest.dto.LoginRequest;
import com.tontiflow.interfaces.rest.dto.RegisterRequest;
import com.tontiflow.interfaces.rest.dto.TokenResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test d'intégration des endpoints {@code /api/v1/auth/register} et
 * {@code /api/v1/auth/login}, ainsi que de la politique d'accès (routes
 * publiques vs protégées).
 *
 * <p>Même pattern que {@code SecurityConfigIntegrationTest} : paire de
 * clés RSA éphémère ({@link JwtTestSecurityConfiguration}), aucune clé
 * réelle. Chaque scénario utilise un email unique afin de rester
 * indépendant de l'ordre d'exécution.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(JwtTestSecurityConfiguration.class)
class AuthControllerIntegrationTest {

    private static final String TEST_PASSWORD = "S3cur3-Test-Passw0rd!";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AuthAccountRepository authAccountRepository;

    @Autowired
    private AccessTokenService accessTokenService;

    @Test
    void register_withValidData_returns201WithEmptyBody() {
        ResponseEntity<Void> response = register("register-ok@tontiflow.test", TEST_PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void register_withInvalidEmail_returns400() {
        ResponseEntity<ErrorResponse> response = restTemplate.postForEntity(
                "/api/v1/auth/register", new RegisterRequest("pas-un-email", TEST_PASSWORD), ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void register_withTooShortPassword_returns400() {
        ResponseEntity<ErrorResponse> response = restTemplate.postForEntity(
                "/api/v1/auth/register", new RegisterRequest("shortpwd@tontiflow.test", "short"), ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void register_withAlreadyUsedEmail_returns409() {
        register("duplicate@tontiflow.test", TEST_PASSWORD);

        ResponseEntity<ErrorResponse> response = restTemplate.postForEntity(
                "/api/v1/auth/register", new RegisterRequest("duplicate@tontiflow.test", TEST_PASSWORD), ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void login_withCorrectCredentials_returns200WithAccessToken() {
        register("login-ok@tontiflow.test", TEST_PASSWORD);

        ResponseEntity<TokenResponse> response = login("login-ok@tontiflow.test", TEST_PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().accessToken()).isNotBlank();
        assertThat(response.getBody().tokenType()).isEqualTo("Bearer");
        assertThat(response.getBody().expiresIn()).isGreaterThan(0);
        assertThat(response.getBody().refreshToken()).isNotBlank();
    }

    @Test
    void login_withUnknownEmail_returns401() {
        ResponseEntity<ErrorResponse> response = loginExpectingError("unknown-login@tontiflow.test", TEST_PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void login_withUnknownEmail_andCorrelationIdHeader_echoesCorrelationId() {
        String correlationId = "test-correlation-id-99";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Correlation-ID", correlationId);
        HttpEntity<LoginRequest> entity = new HttpEntity<>(
                new LoginRequest("unknown-correlation@tontiflow.test", TEST_PASSWORD), headers);

        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
                "/api/v1/auth/login", HttpMethod.POST, entity, ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().correlationId()).isEqualTo(correlationId);
    }

    @Test
    void login_withUnknownEmail_andBlankCorrelationIdHeader_generatesFallbackCorrelationId() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Correlation-ID", "   ");
        HttpEntity<LoginRequest> entity = new HttpEntity<>(
                new LoginRequest("unknown-correlation-blank@tontiflow.test", TEST_PASSWORD), headers);

        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
                "/api/v1/auth/login", HttpMethod.POST, entity, ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().correlationId()).isNotBlank();
        assertThat(response.getBody().correlationId()).isNotEqualTo("   ");
    }

    @Test
    void login_withWrongPassword_returns401() {
        register("wrongpwd-login@tontiflow.test", TEST_PASSWORD);

        ResponseEntity<ErrorResponse> response = loginExpectingError("wrongpwd-login@tontiflow.test", "mot-de-passe-incorrect");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void login_withLockedAccount_returns423() {
        register("locked-login@tontiflow.test", TEST_PASSWORD);
        setStatus("locked-login@tontiflow.test", AccountStatus.LOCKED);

        ResponseEntity<ErrorResponse> response = loginExpectingError("locked-login@tontiflow.test", TEST_PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.LOCKED);
    }

    @Test
    void login_withDisabledAccount_returns403() {
        register("disabled-login@tontiflow.test", TEST_PASSWORD);
        setStatus("disabled-login@tontiflow.test", AccountStatus.DISABLED);

        ResponseEntity<ErrorResponse> response = loginExpectingError("disabled-login@tontiflow.test", TEST_PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void login_returnsTokenThatAccessTokenServiceValidatesWithCorrectUserContext() {
        register("jwt-check@tontiflow.test", TEST_PASSWORD);

        ResponseEntity<TokenResponse> response = login("jwt-check@tontiflow.test", TEST_PASSWORD);
        String token = response.getBody().accessToken();
        AuthAccount account = authAccountRepository.findByEmail("jwt-check@tontiflow.test").orElseThrow();

        // Le token retourne par l'endpoint doit etre reellement valide pour AccessTokenService,
        // le meme composant deja valide qui protege toutes les autres routes.
        UserContext context = accessTokenService.validate(token);

        assertThat(context.userId()).isEqualTo(account.getId());
        assertThat(context.username()).isEqualTo("jwt-check@tontiflow.test");
        assertThat(context.email()).isEqualTo("jwt-check@tontiflow.test");
        assertThat(context.roles()).isEqualTo(Set.of());
        assertThat(context.permissions()).isEqualTo(Set.of());
    }

    @Test
    void register_withoutJwt_isAccessible() {
        ResponseEntity<Void> response = register("no-jwt-register@tontiflow.test", TEST_PASSWORD);

        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void login_withoutJwt_isAccessible() {
        register("no-jwt-login@tontiflow.test", TEST_PASSWORD);

        ResponseEntity<TokenResponse> response = login("no-jwt-login@tontiflow.test", TEST_PASSWORD);

        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void protectedRoute_withoutJwt_returns401() {
        // /actuator/health est volontairement public depuis la decision R11 (corrections
        // techniques, sonde d'orchestration sans JWT) - /actuator/beans reste protege
        // (jamais expose par management.endpoints.web.exposure.include), utilise ici
        // comme route protegee representative.
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/beans", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void health_withoutJwt_isPubliclyAccessible() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private ResponseEntity<Void> register(String email, String password) {
        return restTemplate.postForEntity("/api/v1/auth/register", new RegisterRequest(email, password), Void.class);
    }

    private ResponseEntity<TokenResponse> login(String email, String password) {
        return restTemplate.postForEntity("/api/v1/auth/login", new LoginRequest(email, password), TokenResponse.class);
    }

    // Les scenarios d'echec renvoient un ErrorResponse (pas un TokenResponse) : deserialiser
    // avec le mauvais type exposerait le test a un echec de parsing Jackson non pertinent
    // pour ce qui est reellement teste ici (le code de statut HTTP).
    private ResponseEntity<ErrorResponse> loginExpectingError(String email, String password) {
        return restTemplate.postForEntity("/api/v1/auth/login", new LoginRequest(email, password), ErrorResponse.class);
    }

    private void setStatus(String email, AccountStatus status) {
        AuthAccount account = authAccountRepository.findByEmail(email).orElseThrow();
        account.setStatus(status);
        authAccountRepository.save(account);
    }
}
