package com.tontiflow.interfaces.rest;

import com.tontiflow.domain.model.Tontine;
import com.tontiflow.infrastructure.client.AccountBalanceResponse;
import com.tontiflow.infrastructure.client.FinancialServiceClient;
import com.tontiflow.infrastructure.client.LedgerLineResponse;
import com.tontiflow.infrastructure.repository.TontineRepository;
import com.tontiflow.infrastructure.security.JwtTestSecurityConfiguration;
import com.tontiflow.security.jwt.JwtClaimNames;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Test d'intégration réel du flux de consultation de solde (décision R7) —
 * symétrique à {@code ContributionIntegrationTest}/{@code
 * DisbursementIntegrationTest} (décisions R3/R6). {@link
 * FinancialServiceClient} mocké ({@code @MockBean}) : ce test prouve
 * l'autorisation côté tontine-service ; le comportement réel du solde
 * dérivé du Ledger est prouvé séparément côté financial-service
 * ({@code AccountBalanceControllerIntegrationTest}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(JwtTestSecurityConfiguration.class)
class BalanceIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private KeyPair jwtTestKeyPair;
    @Autowired
    private TontineRepository tontineRepository;
    @MockBean
    private FinancialServiceClient financialServiceClient;

    @Test
    void getBalance_asCreator_returnsBalanceFromFinancialService() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontineAndGetId(creator);
        when(financialServiceClient.getBalance(eq(tontineId), anyString()))
                .thenReturn(new AccountBalanceResponse("MRU", new BigDecimal("700.00")));

        ResponseEntity<String> response = exchangeWithBearer(
                "/api/v1/tontines/" + tontineId + "/balance", creator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("700.00").contains("MRU");
        verify(financialServiceClient).getBalance(eq(tontineId), anyString());
    }

    @Test
    void getBalance_asNonCreator_isForbidden_andFinancialServiceNeverCalled() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontineAndGetId(creator);

        ResponseEntity<String> response = exchangeWithBearer(
                "/api/v1/tontines/" + tontineId + "/balance", UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(financialServiceClient, never()).getBalance(any(), anyString());
    }

    @Test
    void getBalance_withUnknownTontine_isNotFound_andFinancialServiceNeverCalled() {
        ResponseEntity<String> response = exchangeWithBearer(
                "/api/v1/tontines/999999/balance", UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(financialServiceClient, never()).getBalance(any(), anyString());
    }

    @Test
    void getBalance_withoutToken_isRejectedWithUnauthorized() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/tontines/1/balance", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(financialServiceClient, never()).getBalance(any(), anyString());
    }

    @Test
    void getBalance_withInvalidToken_isRejectedWithUnauthorized() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair untrustedKeyPair = generator.generateKeyPair();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(buildToken(untrustedKeyPair, UUID.randomUUID()));
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/tontines/1/balance", HttpMethod.GET, entity, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void getBalance_whenFinancialServiceFails_returnsConflict_sameConventionAsExistingHandler() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontineAndGetId(creator);
        when(financialServiceClient.getBalance(eq(tontineId), anyString()))
                .thenThrow(new IllegalStateException("Échec de la consultation du solde auprès de financial-service"));

        ResponseEntity<String> response = exchangeWithBearer(
                "/api/v1/tontines/" + tontineId + "/balance", creator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void getStatement_asCreator_returnsLinesFromFinancialService() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontineAndGetId(creator);
        when(financialServiceClient.getStatement(eq(tontineId), anyString())).thenReturn(List.of(
                new LedgerLineResponse("CONTRIBUTION_RECORDED", "Contribution round 1 tontine " + tontineId,
                        new BigDecimal("1000.00"), BigDecimal.ZERO, "MRU", Instant.now())));

        ResponseEntity<String> response = exchangeWithBearer(
                "/api/v1/tontines/" + tontineId + "/statement", creator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("CONTRIBUTION_RECORDED").contains("1000.00");
        verify(financialServiceClient).getStatement(eq(tontineId), anyString());
    }

    @Test
    void getStatement_asNonCreator_isForbidden_andFinancialServiceNeverCalled() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontineAndGetId(creator);

        ResponseEntity<String> response = exchangeWithBearer(
                "/api/v1/tontines/" + tontineId + "/statement", UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(financialServiceClient, never()).getStatement(any(), anyString());
    }

    @Test
    void getStatement_withUnknownTontine_isNotFound_andFinancialServiceNeverCalled() {
        ResponseEntity<String> response = exchangeWithBearer(
                "/api/v1/tontines/999999/statement", UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(financialServiceClient, never()).getStatement(any(), anyString());
    }

    @Test
    void getStatement_withoutToken_isRejectedWithUnauthorized() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/tontines/1/statement", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(financialServiceClient, never()).getStatement(any(), anyString());
    }

    private Long createTontineAndGetId(UUID creator) {
        Tontine tontine = new Tontine();
        tontine.setName("Tontine de test R7");
        tontine.setCreatorUserId(creator);
        tontine.setCreatedAt(LocalDateTime.now());
        return tontineRepository.save(tontine).getId();
    }

    private ResponseEntity<String> exchangeWithBearer(String path, UUID subject) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(buildToken(jwtTestKeyPair, subject));
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        return restTemplate.exchange(path, HttpMethod.GET, entity, String.class);
    }

    private static String buildToken(KeyPair signingKeyPair, UUID subject) {
        Instant now = Instant.now();
        return Jwts.builder()
                .claim(JwtClaimNames.SUBJECT, subject.toString())
                .claim(JwtClaimNames.ISSUED_AT, Date.from(now))
                .claim(JwtClaimNames.EXPIRATION, Date.from(now.plus(15, ChronoUnit.MINUTES)))
                .claim(JwtClaimNames.JWT_ID, UUID.randomUUID().toString())
                .claim(JwtClaimNames.ISSUER, "authentication-service")
                .claim(JwtClaimNames.USERNAME, "creator")
                .claim(JwtClaimNames.EMAIL, "creator@tontiflow.test")
                .claim(JwtClaimNames.ROLES, List.copyOf(Set.of("ROLE_USER")))
                .claim(JwtClaimNames.PERMISSIONS, List.copyOf(Set.<String>of()))
                .signWith(signingKeyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }
}
