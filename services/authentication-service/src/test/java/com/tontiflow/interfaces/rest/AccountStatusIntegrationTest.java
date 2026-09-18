package com.tontiflow.interfaces.rest;

import com.tontiflow.UserContext;
import com.tontiflow.core.dto.ErrorResponse;
import com.tontiflow.domain.enums.AccountStatus;
import com.tontiflow.domain.model.AccountStatusChange;
import com.tontiflow.domain.model.AuthAccount;
import com.tontiflow.domain.model.Role;
import com.tontiflow.infrastructure.repository.AccountStatusChangeRepository;
import com.tontiflow.infrastructure.repository.AuthAccountRepository;
import com.tontiflow.infrastructure.repository.RefreshTokenRepository;
import com.tontiflow.infrastructure.repository.RoleRepository;
import com.tontiflow.infrastructure.security.jwt.AccessTokenService;
import com.tontiflow.infrastructure.security.jwt.JwtTestSecurityConfiguration;
import com.tontiflow.interfaces.rest.dto.AccountStatusResponse;
import com.tontiflow.interfaces.rest.dto.LoginRequest;
import com.tontiflow.interfaces.rest.dto.RefreshTokenRequest;
import com.tontiflow.interfaces.rest.dto.RegisterRequest;
import com.tontiflow.interfaces.rest.dto.TokenResponse;
import com.tontiflow.interfaces.rest.dto.UpdateAccountStatusRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test d'intégration du changement administratif de statut de compte
 * (décision R21-RD) : {@code PUT /api/v1/admin/accounts/{accountId}/status}.
 *
 * <p>Même pattern que {@code RbacControllerIntegrationTest} : paire de clés
 * RSA éphémère ({@link JwtTestSecurityConfiguration}), {@code ROLE_ADMIN}
 * créé directement via {@link RoleRepository} (profil test, Flyway
 * désactivé).</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(JwtTestSecurityConfiguration.class)
class AccountStatusIntegrationTest {

    private static final String TEST_PASSWORD = "S3cur3-Test-Passw0rd!";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AuthAccountRepository authAccountRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private AccountStatusChangeRepository accountStatusChangeRepository;

    @Autowired
    private AccessTokenService accessTokenService;

    // ------------------------------------------------------------------
    // Transitions autorisees (decision R21-RD, D2)
    // ------------------------------------------------------------------

    @Test
    void updateAccountStatus_activeToLocked_succeeds_andBlocksSubsequentLogin() {
        String adminToken = provisionAdmin("astatus-admin-1@tontiflow.test");
        AuthAccount target = registerAccount("astatus-target-1@tontiflow.test");

        ResponseEntity<AccountStatusResponse> response = updateStatus(
                adminToken, target.getId(), AccountStatus.LOCKED, "Suspicion de fraude");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo(AccountStatus.LOCKED);
        assertThat(response.getBody().accountId()).isEqualTo(target.getId());
        assertThat(response.getBody().email()).isEqualTo(target.getEmail());

        ResponseEntity<ErrorResponse> loginAfter = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(target.getEmail(), TEST_PASSWORD), ErrorResponse.class);
        assertThat(loginAfter.getStatusCode()).isEqualTo(HttpStatus.LOCKED);
    }

    @Test
    void updateAccountStatus_activeToDisabled_succeeds_andBlocksSubsequentLogin() {
        String adminToken = provisionAdmin("astatus-admin-2@tontiflow.test");
        AuthAccount target = registerAccount("astatus-target-2@tontiflow.test");

        ResponseEntity<AccountStatusResponse> response = updateStatus(
                adminToken, target.getId(), AccountStatus.DISABLED, "Demande legale");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo(AccountStatus.DISABLED);

        ResponseEntity<ErrorResponse> loginAfter = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(target.getEmail(), TEST_PASSWORD), ErrorResponse.class);
        assertThat(loginAfter.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void updateAccountStatus_lockedToActive_succeeds_andLoginWorksAgain() {
        String adminToken = provisionAdmin("astatus-admin-3@tontiflow.test");
        AuthAccount target = registerAccount("astatus-target-3@tontiflow.test");
        updateStatus(adminToken, target.getId(), AccountStatus.LOCKED, "Motif initial");

        ResponseEntity<AccountStatusResponse> response = updateStatus(
                adminToken, target.getId(), AccountStatus.ACTIVE, "Verification effectuee");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo(AccountStatus.ACTIVE);
        ResponseEntity<TokenResponse> loginAfter = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(target.getEmail(), TEST_PASSWORD), TokenResponse.class);
        assertThat(loginAfter.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void updateAccountStatus_lockedToDisabled_succeeds() {
        String adminToken = provisionAdmin("astatus-admin-4@tontiflow.test");
        AuthAccount target = registerAccount("astatus-target-4@tontiflow.test");
        updateStatus(adminToken, target.getId(), AccountStatus.LOCKED, "Motif initial");

        ResponseEntity<AccountStatusResponse> response = updateStatus(
                adminToken, target.getId(), AccountStatus.DISABLED, "Aggravation");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo(AccountStatus.DISABLED);
    }

    @Test
    void updateAccountStatus_disabledToActive_succeeds() {
        String adminToken = provisionAdmin("astatus-admin-5@tontiflow.test");
        AuthAccount target = registerAccount("astatus-target-5@tontiflow.test");
        updateStatus(adminToken, target.getId(), AccountStatus.DISABLED, "Motif initial");

        ResponseEntity<AccountStatusResponse> response = updateStatus(
                adminToken, target.getId(), AccountStatus.ACTIVE, "Reactivation autorisee");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void updateAccountStatus_disabledToLocked_isRejected400() {
        // Decision R21-RD D2 : transition explicitement interdite.
        String adminToken = provisionAdmin("astatus-admin-6@tontiflow.test");
        AuthAccount target = registerAccount("astatus-target-6@tontiflow.test");
        updateStatus(adminToken, target.getId(), AccountStatus.DISABLED, "Motif initial");

        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
                "/api/v1/admin/accounts/" + target.getId() + "/status", HttpMethod.PUT,
                withBearer(adminToken, new UpdateAccountStatusRequest(AccountStatus.LOCKED, "Tentative interdite")),
                ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // Compte inchange.
        assertThat(authAccountRepository.findById(target.getId()).orElseThrow().getStatus())
                .isEqualTo(AccountStatus.DISABLED);
    }

    // ------------------------------------------------------------------
    // Idempotence (decision R21-RD, D3)
    // ------------------------------------------------------------------

    @Test
    void updateAccountStatus_targetAlreadyApplied_isIdempotent_noAdditionalAuditEvent() {
        String adminToken = provisionAdmin("astatus-admin-7@tontiflow.test");
        AuthAccount target = registerAccount("astatus-target-7@tontiflow.test");

        ResponseEntity<AccountStatusResponse> first = updateStatus(
                adminToken, target.getId(), AccountStatus.ACTIVE, "Motif idempotent");
        ResponseEntity<AccountStatusResponse> second = updateStatus(
                adminToken, target.getId(), AccountStatus.ACTIVE, "Autre motif, sans effet");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().status()).isEqualTo(AccountStatus.ACTIVE);

        List<AccountStatusChange> events = accountStatusChangeRepository.findByAccountId(target.getId());
        assertThat(events).isEmpty(); // ACTIVE -> ACTIVE : jamais un vrai changement, aucun evenement
    }

    @Test
    void updateAccountStatus_realTransitionThenIdempotentRepeat_createsExactlyOneAuditEvent() {
        String adminToken = provisionAdmin("astatus-admin-8@tontiflow.test");
        AuthAccount target = registerAccount("astatus-target-8@tontiflow.test");

        updateStatus(adminToken, target.getId(), AccountStatus.LOCKED, "Premier motif");
        updateStatus(adminToken, target.getId(), AccountStatus.LOCKED, "Motif repete, sans effet");

        List<AccountStatusChange> events = accountStatusChangeRepository.findByAccountId(target.getId());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getReason()).isEqualTo("Premier motif");
    }

    // ------------------------------------------------------------------
    // Compte inexistant / motif invalide
    // ------------------------------------------------------------------

    @Test
    void updateAccountStatus_unknownAccount_returns404() {
        String adminToken = provisionAdmin("astatus-admin-9@tontiflow.test");

        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
                "/api/v1/admin/accounts/" + UUID.randomUUID() + "/status", HttpMethod.PUT,
                withBearer(adminToken, new UpdateAccountStatusRequest(AccountStatus.LOCKED, "Motif")),
                ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void updateAccountStatus_missingReason_returns400() {
        String adminToken = provisionAdmin("astatus-admin-10@tontiflow.test");
        AuthAccount target = registerAccount("astatus-target-10@tontiflow.test");

        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
                "/api/v1/admin/accounts/" + target.getId() + "/status", HttpMethod.PUT,
                withBearer(adminToken, new UpdateAccountStatusRequest(AccountStatus.LOCKED, null)),
                ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void updateAccountStatus_blankReason_returns400() {
        String adminToken = provisionAdmin("astatus-admin-11@tontiflow.test");
        AuthAccount target = registerAccount("astatus-target-11@tontiflow.test");

        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
                "/api/v1/admin/accounts/" + target.getId() + "/status", HttpMethod.PUT,
                withBearer(adminToken, new UpdateAccountStatusRequest(AccountStatus.LOCKED, "    ")),
                ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void updateAccountStatus_tooLongReason_returns400() {
        String adminToken = provisionAdmin("astatus-admin-12@tontiflow.test");
        AuthAccount target = registerAccount("astatus-target-12@tontiflow.test");
        String tooLong = "x".repeat(256);

        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
                "/api/v1/admin/accounts/" + target.getId() + "/status", HttpMethod.PUT,
                withBearer(adminToken, new UpdateAccountStatusRequest(AccountStatus.LOCKED, tooLong)),
                ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ------------------------------------------------------------------
    // Autorisation (decision R21-RD, D5 - reutilisation stricte de ROLE_ADMIN)
    // ------------------------------------------------------------------

    @Test
    void updateAccountStatus_withoutJwt_returns401() {
        AuthAccount target = registerAccount("astatus-target-13@tontiflow.test");

        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
                "/api/v1/admin/accounts/" + target.getId() + "/status", HttpMethod.PUT,
                new HttpEntity<>(new UpdateAccountStatusRequest(AccountStatus.LOCKED, "Motif")),
                ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void updateAccountStatus_withAuthenticatedNonAdmin_returns403() {
        AuthAccount plain = registerAccount("astatus-plain-14@tontiflow.test");
        String token = accessTokenService.generate(
                new UserContext(plain.getId(), plain.getEmail(), plain.getEmail(), Set.of(), Set.of()));
        AuthAccount target = registerAccount("astatus-target-14@tontiflow.test");

        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
                "/api/v1/admin/accounts/" + target.getId() + "/status", HttpMethod.PUT,
                withBearer(token, new UpdateAccountStatusRequest(AccountStatus.LOCKED, "Motif")),
                ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // Le compte cible n'a pas ete modifie par cette tentative non autorisee.
        assertThat(authAccountRepository.findById(target.getId()).orElseThrow().getStatus())
                .isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void updateAccountStatus_actorIdentity_isDerivedFromJwt_neverFromRequestBody() {
        // UpdateAccountStatusRequest ne porte aucun champ acteur/adminId : impossible de le
        // falsifier depuis le corps. On verifie que l'evenement d'audit persiste bien
        // l'identite REELLE de l'administrateur authentifie (caller.userId()).
        String adminEmail = "astatus-admin-15@tontiflow.test";
        String adminToken = provisionAdmin(adminEmail);
        AuthAccount adminAccount = authAccountRepository.findByEmail(adminEmail).orElseThrow();
        AuthAccount target = registerAccount("astatus-target-15@tontiflow.test");

        updateStatus(adminToken, target.getId(), AccountStatus.LOCKED, "Verification acteur");

        List<AccountStatusChange> events = accountStatusChangeRepository.findByAccountId(target.getId());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getActorAccountId()).isEqualTo(adminAccount.getId());
    }

    // ------------------------------------------------------------------
    // Sessions et JWT (decision R21-RD, D6) - point de securite critique
    // ------------------------------------------------------------------

    @Test
    void refresh_afterAccountLockedViaAdminEndpoint_fails_becauseFamilyAlreadyRevoked() {
        String adminToken = provisionAdmin("astatus-admin-16@tontiflow.test");
        AuthAccount target = registerAccount("astatus-target-16@tontiflow.test");
        ResponseEntity<TokenResponse> login = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(target.getEmail(), TEST_PASSWORD), TokenResponse.class);
        String refreshToken = login.getBody().refreshToken();

        updateStatus(adminToken, target.getId(), AccountStatus.LOCKED, "Compromission suspectee");

        // La famille du refresh token presente a deja ete revoquee par l'action admin
        // (D6) : rotate() echoue lui-meme avant meme d'atteindre la verification de
        // statut de AuthAccountService.findById() - voir javadoc de cette derniere.
        ResponseEntity<ErrorResponse> refreshAfter = restTemplate.postForEntity(
                "/api/v1/auth/refresh", new RefreshTokenRequest(refreshToken), ErrorResponse.class);

        assertThat(refreshAfter.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refresh_beforeAnyStatusChange_stillSucceeds_nonRegression() {
        AuthAccount target = registerAccount("astatus-target-17@tontiflow.test");
        ResponseEntity<TokenResponse> login = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(target.getEmail(), TEST_PASSWORD), TokenResponse.class);

        ResponseEntity<TokenResponse> refresh = restTemplate.postForEntity(
                "/api/v1/auth/refresh", new RefreshTokenRequest(login.getBody().refreshToken()), TokenResponse.class);

        assertThat(refresh.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void updateAccountStatus_revokesAllActiveFamilies_acrossMultipleDevices() {
        String adminToken = provisionAdmin("astatus-admin-18@tontiflow.test");
        AuthAccount target = registerAccount("astatus-target-18@tontiflow.test");

        // Deux "appareils" distincts : deux connexions independantes, deux familles.
        ResponseEntity<TokenResponse> loginDeviceOne = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(target.getEmail(), TEST_PASSWORD), TokenResponse.class);
        ResponseEntity<TokenResponse> loginDeviceTwo = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(target.getEmail(), TEST_PASSWORD), TokenResponse.class);

        updateStatus(adminToken, target.getId(), AccountStatus.DISABLED, "Desactivation multi-appareils");

        ResponseEntity<ErrorResponse> refreshOne = restTemplate.postForEntity(
                "/api/v1/auth/refresh", new RefreshTokenRequest(loginDeviceOne.getBody().refreshToken()), ErrorResponse.class);
        ResponseEntity<ErrorResponse> refreshTwo = restTemplate.postForEntity(
                "/api/v1/auth/refresh", new RefreshTokenRequest(loginDeviceTwo.getBody().refreshToken()), ErrorResponse.class);

        assertThat(refreshOne.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(refreshTwo.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void updateAccountStatus_neverRevokesFamiliesOfOtherAccounts() {
        String adminToken = provisionAdmin("astatus-admin-19@tontiflow.test");
        AuthAccount target = registerAccount("astatus-target-19@tontiflow.test");
        AuthAccount other = registerAccount("astatus-other-19@tontiflow.test");
        ResponseEntity<TokenResponse> otherLogin = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(other.getEmail(), TEST_PASSWORD), TokenResponse.class);

        updateStatus(adminToken, target.getId(), AccountStatus.DISABLED, "Ne concerne pas l'autre compte");

        ResponseEntity<TokenResponse> otherRefresh = restTemplate.postForEntity(
                "/api/v1/auth/refresh", new RefreshTokenRequest(otherLogin.getBody().refreshToken()), TokenResponse.class);
        assertThat(otherRefresh.getStatusCode()).isEqualTo(HttpStatus.OK); // jamais affecte
    }

    @Test
    void accessTokenAlreadyIssued_remainsValidImmediatelyAfterStatusChange_residualWindowDocumentedByEvidence() {
        // Decision R21-RD D6 : "ne pas pretendre que les JWT d'acces deja delivres sont
        // immediatement invalides" - preuve directe et non une simple affirmation.
        String adminToken = provisionAdmin("astatus-admin-20@tontiflow.test");
        AuthAccount target = registerAccount("astatus-target-20@tontiflow.test");
        ResponseEntity<TokenResponse> login = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(target.getEmail(), TEST_PASSWORD), TokenResponse.class);
        String accessTokenIssuedBeforeChange = login.getBody().accessToken();

        updateStatus(adminToken, target.getId(), AccountStatus.DISABLED, "Access token deja emis avant ceci");

        // Validation purement cryptographique (AccessTokenService.validate) : aucun acces
        // DB, donc aucune connaissance du changement de statut qui vient d'avoir lieu.
        UserContext contextFromOldToken = accessTokenService.validate(accessTokenIssuedBeforeChange);
        assertThat(contextFromOldToken.userId()).isEqualTo(target.getId());
    }

    // ------------------------------------------------------------------
    // Reponse - absence de fuite du motif (decision R21-RD, D4/D8)
    // ------------------------------------------------------------------

    @Test
    void updateAccountStatus_responseBody_neverContainsReason() {
        String adminToken = provisionAdmin("astatus-admin-21@tontiflow.test");
        AuthAccount target = registerAccount("astatus-target-21@tontiflow.test");
        String sensitiveReason = "MotifSensibleUniqueXYZ123";

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/accounts/" + target.getId() + "/status", HttpMethod.PUT,
                withBearer(adminToken, new UpdateAccountStatusRequest(AccountStatus.LOCKED, sensitiveReason)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).doesNotContain(sensitiveReason);
    }

    // ------------------------------------------------------------------
    // Helpers (memes conventions que RbacControllerIntegrationTest)
    // ------------------------------------------------------------------

    private ResponseEntity<AccountStatusResponse> updateStatus(String adminToken, UUID accountId,
                                                                 AccountStatus targetStatus, String reason) {
        return restTemplate.exchange(
                "/api/v1/admin/accounts/" + accountId + "/status", HttpMethod.PUT,
                withBearer(adminToken, new UpdateAccountStatusRequest(targetStatus, reason)),
                AccountStatusResponse.class);
    }

    private String provisionAdmin(String email) {
        AuthAccount account = registerAccount(email);
        Role adminRole = roleRepository.findByName("ROLE_ADMIN").orElseGet(() -> persistRole("ROLE_ADMIN"));
        account.setRoles(new HashSet<>(Set.of(adminRole)));
        authAccountRepository.save(account);

        ResponseEntity<TokenResponse> response = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), TokenResponse.class);
        return response.getBody().accessToken();
    }

    private AuthAccount registerAccount(String email) {
        restTemplate.postForEntity("/api/v1/auth/register", new RegisterRequest(email, TEST_PASSWORD), Void.class);
        return authAccountRepository.findByEmail(email).orElseThrow();
    }

    private Role persistRole(String name) {
        Role role = new Role();
        role.setName(name);
        return roleRepository.save(role);
    }

    private static <T> HttpEntity<T> withBearer(String token, T body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(body, headers);
    }
}
