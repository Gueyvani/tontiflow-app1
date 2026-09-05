package com.tontiflow.interfaces.rest;

import com.tontiflow.domain.enums.RoundStatus;
import com.tontiflow.domain.model.Tontine;
import com.tontiflow.domain.model.TontineRound;
import com.tontiflow.infrastructure.client.FinancialServiceClient;
import com.tontiflow.infrastructure.repository.TontineRepository;
import com.tontiflow.infrastructure.repository.TontineRoundRepository;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.security.KeyPair;
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

/**
 * Test d'intégration réel du flux de versement (décision R6) — symétrique à
 * {@code ContributionIntegrationTest} (décision R3). {@link
 * FinancialServiceClient} mocké ({@code @MockBean}) : ce test prouve
 * l'autorisation/validation côté tontine-service ; le comportement réel du
 * Ledger est prouvé séparément côté financial-service.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(JwtTestSecurityConfiguration.class)
class DisbursementIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private KeyPair jwtTestKeyPair;
    @Autowired
    private TontineRepository tontineRepository;
    @Autowired
    private TontineRoundRepository roundRepository;
    @MockBean
    private FinancialServiceClient financialServiceClient;

    @Test
    void recordDisbursement_withoutToken_isRejectedWithUnauthorized() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/tontines/1/rounds/1/disbursements", null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(financialServiceClient, never()).recordDisbursement(any(), any(), any(), any(), anyString());
    }

    @Test
    void recordDisbursement_asCreator_withAssignedBeneficiary_succeeds() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontineAndGetId(creator);
        Long roundId = createRoundAndGetId(tontineId, new BigDecimal("5000.00"), 100L);

        ResponseEntity<String> response = exchangeWithBearer(
                "/api/v1/tontines/" + tontineId + "/rounds/" + roundId + "/disbursements", creator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(financialServiceClient).recordDisbursement(
                eq(tontineId), eq(roundId), eq(100L), eq(new BigDecimal("5000.00")), anyString());
    }

    @Test
    void recordDisbursement_asNonCreator_isForbidden() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontineAndGetId(creator);
        Long roundId = createRoundAndGetId(tontineId, new BigDecimal("5000.00"), 100L);

        ResponseEntity<String> response = exchangeWithBearer(
                "/api/v1/tontines/" + tontineId + "/rounds/" + roundId + "/disbursements", UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(financialServiceClient, never()).recordDisbursement(any(), any(), any(), any(), anyString());
    }

    @Test
    void recordDisbursement_withRoundFromAnotherTontine_isRejected() {
        UUID creatorA = UUID.randomUUID();
        Long tontineA = createTontineAndGetId(creatorA);

        UUID creatorB = UUID.randomUUID();
        Long tontineB = createTontineAndGetId(creatorB);
        Long roundOfB = createRoundAndGetId(tontineB, new BigDecimal("1000.00"), 200L);

        ResponseEntity<String> response = exchangeWithBearer(
                "/api/v1/tontines/" + tontineA + "/rounds/" + roundOfB + "/disbursements", creatorA);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(financialServiceClient, never()).recordDisbursement(any(), any(), any(), any(), anyString());
    }

    // TEST contrainte de donnee (decision R6) : round sans beneficiaire assigne.
    @Test
    void recordDisbursement_withNoBeneficiaryAssigned_isRejected() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontineAndGetId(creator);
        Long roundId = createRoundAndGetId(tontineId, new BigDecimal("1000.00"), null);

        ResponseEntity<String> response = exchangeWithBearer(
                "/api/v1/tontines/" + tontineId + "/rounds/" + roundId + "/disbursements", creator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(financialServiceClient, never()).recordDisbursement(any(), any(), any(), any(), anyString());
    }

    private Long createTontineAndGetId(UUID creator) {
        Tontine tontine = new Tontine();
        tontine.setName("Tontine de test R6");
        tontine.setCreatorUserId(creator);
        tontine.setCreatedAt(LocalDateTime.now());
        return tontineRepository.save(tontine).getId();
    }

    private Long createRoundAndGetId(Long tontineId, BigDecimal amount, Long beneficiaryId) {
        TontineRound round = new TontineRound();
        round.setTontineId(tontineId);
        round.setRoundNumber(1);
        round.setAmount(amount);
        round.setBeneficiaryId(beneficiaryId);
        round.setStatus(RoundStatus.ASSIGNED);
        round.setStartDate(LocalDateTime.now());
        round.setEndDate(LocalDateTime.now().plusDays(30));
        return roundRepository.save(round).getId();
    }

    private ResponseEntity<String> exchangeWithBearer(String path, UUID subject) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(validToken(subject));
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        return restTemplate.postForEntity(path, entity, String.class);
    }

    private String validToken(UUID subject) {
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
                .signWith(jwtTestKeyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }
}
