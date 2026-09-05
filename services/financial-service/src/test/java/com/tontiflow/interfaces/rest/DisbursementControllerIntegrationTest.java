package com.tontiflow.interfaces.rest;

import com.tontiflow.core.dto.ErrorResponse;
import com.tontiflow.domain.enums.Currency;
import com.tontiflow.infrastructure.repository.JournalEntryRepository;
import com.tontiflow.infrastructure.repository.LedgerLineRepository;
import com.tontiflow.infrastructure.security.JwtTestSecurityConfiguration;
import com.tontiflow.interfaces.rest.dto.RecordDisbursementRequest;
import com.tontiflow.security.jwt.JwtClaimNames;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.security.KeyPair;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test d'intégration réel de {@link DisbursementController} (décision R6) —
 * symétrique à {@code ContributionControllerIntegrationTest} (décision R3).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(JwtTestSecurityConfiguration.class)
class DisbursementControllerIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private KeyPair jwtTestKeyPair;
    @Autowired
    private JournalEntryRepository journalEntryRepository;
    @Autowired
    private LedgerLineRepository ledgerLineRepository;

    @Test
    void recordDisbursement_withoutJwt_isRejectedWithUnauthorized() {
        RecordDisbursementRequest request = new RecordDisbursementRequest(
                10L, 25L, 123L, new BigDecimal("5000.00"), Currency.MRU);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/internal/disbursements", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void recordDisbursement_withValidToken_createsBalancedJournalEntry() {
        RecordDisbursementRequest request = new RecordDisbursementRequest(
                10L, 25L, 123L, new BigDecimal("5000.00"), Currency.MRU);

        ResponseEntity<Void> response = postWithBearer(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var entry = journalEntryRepository.findByIdempotencyKey("disbursement:10:25:123").orElseThrow();

        var lines = ledgerLineRepository.findAll().stream()
                .filter(l -> l.getJournalEntry().getId().equals(entry.getId()))
                .toList();
        assertThat(lines).hasSize(2);
        BigDecimal totalDebit = lines.stream().map(l -> l.getDebit()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredit = lines.stream().map(l -> l.getCredit()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(totalDebit).isEqualByComparingTo(totalCredit);
        assertThat(totalDebit).isEqualByComparingTo("5000.00");
    }

    @Test
    void recordDisbursement_replayedWithSameIdentifiers_doesNotDuplicate() {
        RecordDisbursementRequest request = new RecordDisbursementRequest(
                11L, 26L, 124L, new BigDecimal("2000.00"), Currency.MRU);

        ResponseEntity<Void> first = postWithBearer(request);
        ResponseEntity<Void> second = postWithBearer(request);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        long count = journalEntryRepository.findAll().stream()
                .filter(e -> "disbursement:11:26:124".equals(e.getIdempotencyKey()))
                .count();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void recordDisbursement_withNegativeAmount_isRejected() {
        RecordDisbursementRequest request = new RecordDisbursementRequest(
                12L, 27L, 125L, new BigDecimal("-10.00"), Currency.MRU);

        ResponseEntity<ErrorResponse> response = postWithBearerExpectingError(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private ResponseEntity<Void> postWithBearer(RecordDisbursementRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(validToken());
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<RecordDisbursementRequest> entity = new HttpEntity<>(request, headers);
        return restTemplate.postForEntity("/internal/disbursements", entity, Void.class);
    }

    private ResponseEntity<ErrorResponse> postWithBearerExpectingError(RecordDisbursementRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(validToken());
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<RecordDisbursementRequest> entity = new HttpEntity<>(request, headers);
        return restTemplate.postForEntity("/internal/disbursements", entity, ErrorResponse.class);
    }

    private String validToken() {
        Instant now = Instant.now();
        return Jwts.builder()
                .claim(JwtClaimNames.SUBJECT, UUID.randomUUID().toString())
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
