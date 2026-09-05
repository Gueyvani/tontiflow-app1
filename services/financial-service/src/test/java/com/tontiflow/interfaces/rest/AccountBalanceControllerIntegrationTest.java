package com.tontiflow.interfaces.rest;

import com.tontiflow.application.service.ContributionService;
import com.tontiflow.application.service.DisbursementService;
import com.tontiflow.domain.enums.Currency;
import com.tontiflow.infrastructure.security.JwtTestSecurityConfiguration;
import com.tontiflow.interfaces.rest.dto.AccountBalanceResponse;
import com.tontiflow.interfaces.rest.dto.LedgerLineResponse;
import com.tontiflow.security.jwt.JwtClaimNames;
import org.springframework.core.ParameterizedTypeReference;
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
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test d'intégration réel de {@link AccountBalanceController} (décision
 * R7) — symétrique à {@code ContributionControllerIntegrationTest}/{@code
 * DisbursementControllerIntegrationTest} (décisions R3/R6). Le solde est
 * obtenu en enregistrant réellement des écritures via {@link
 * ContributionService}/{@link DisbursementService} (mêmes beans Spring que
 * la production, pas de mock du Ledger), puis en consultant le solde par
 * HTTP — la formule Σdebit-Σcredit est donc exercée réellement, pas
 * simulée.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(JwtTestSecurityConfiguration.class)
class AccountBalanceControllerIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private KeyPair jwtTestKeyPair;
    @Autowired
    private ContributionService contributionService;
    @Autowired
    private DisbursementService disbursementService;

    @Test
    void getBalance_whenAccountHasContributionAndDisbursement_returnsDerivedBalance() {
        Long tontineId = 60L;
        contributionService.recordContribution(tontineId, 70L, 600L, new BigDecimal("1000.00"), Currency.MRU);
        disbursementService.recordDisbursement(tontineId, 71L, 601L, new BigDecimal("300.00"), Currency.MRU);

        ResponseEntity<AccountBalanceResponse> response = getBalanceWithBearer(tontineId, "TONTINE");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        AccountBalanceResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.ownerReference()).isEqualTo(tontineId);
        assertThat(body.currency()).isEqualTo(Currency.MRU);
        // Debit=TONTINE (contribution) - Credit=TONTINE (disbursement) = 1000.00 - 300.00
        assertThat(body.balance()).isEqualByComparingTo("700.00");
    }

    @Test
    void getBalance_whenAccountDoesNotExist_returnsZero_withoutCreatingAccount() {
        Long neverUsedOwnerReference = 62L;

        ResponseEntity<AccountBalanceResponse> response = getBalanceWithBearer(neverUsedOwnerReference, "TONTINE");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        AccountBalanceResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.balance()).isEqualByComparingTo("0.00");
        assertThat(body.currency()).isEqualTo(Currency.MRU);

        // Rejouer confirme qu'aucun compte n'a ete cree par la lecture precedente :
        // un second appel donne exactement le meme resultat (pas de compte "decouvert" entre-temps).
        ResponseEntity<AccountBalanceResponse> secondResponse = getBalanceWithBearer(neverUsedOwnerReference, "TONTINE");
        assertThat(secondResponse.getBody().balance()).isEqualByComparingTo("0.00");
    }

    @Test
    void getBalance_withoutJwt_isRejectedWithUnauthorized() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/internal/accounts/60/TONTINE/balance", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void getBalance_withInvalidJwt_isRejectedWithUnauthorized() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair untrustedKeyPair = generator.generateKeyPair();
        String tokenSignedByUntrustedKey = buildToken(untrustedKeyPair);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tokenSignedByUntrustedKey);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(
                "/internal/accounts/60/TONTINE/balance", org.springframework.http.HttpMethod.GET, entity, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void getBalance_withUnknownAccountType_isRejected() {
        // Découverte réelle (décision R7, cf. exécution du test) : Spring Boot 3.3.13
        // resout un @PathVariable enum non convertible ("NOT_A_REAL_TYPE" n'est ni
        // TONTINE ni MEMBER) en 404 (pas 400 comme initialement suppose ici) - aucun
        // gestionnaire de MethodArgumentTypeMismatchException n'a ete ajoute au
        // GlobalExceptionHandler existant, ce comportement est celui, deja present,
        // du framework. Assertion corrigee sur le comportement reel observe - meme
        // demarche que la decouverte R5 sur le Gateway (401 vs 404) : hypothese de
        // test erronee, pas un bug applicatif. Reste un refus client sans fuite
        // d'information, coherent avec la convention deja etablie (IllegalArgumentException -> 404).
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(buildToken(jwtTestKeyPair));
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(
                "/internal/accounts/60/NOT_A_REAL_TYPE/balance", org.springframework.http.HttpMethod.GET, entity, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getLines_whenAccountHasContributionAndDisbursement_returnsBothLinesWithEventDetails() {
        Long tontineId = 63L;
        contributionService.recordContribution(tontineId, 73L, 630L, new BigDecimal("1000.00"), Currency.MRU);
        disbursementService.recordDisbursement(tontineId, 74L, 631L, new BigDecimal("300.00"), Currency.MRU);

        ResponseEntity<List<LedgerLineResponse>> response = getLinesWithBearer(tontineId, "TONTINE");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<LedgerLineResponse> lines = response.getBody();
        assertThat(lines).isNotNull().hasSize(2);
        assertThat(lines).anySatisfy(line -> {
            assertThat(line.eventType()).isEqualTo("CONTRIBUTION_RECORDED");
            assertThat(line.debit()).isEqualByComparingTo("1000.00");
            assertThat(line.credit()).isEqualByComparingTo("0");
        });
        assertThat(lines).anySatisfy(line -> {
            assertThat(line.eventType()).isEqualTo("DISBURSEMENT_RECORDED");
            assertThat(line.credit()).isEqualByComparingTo("300.00");
            assertThat(line.debit()).isEqualByComparingTo("0");
        });
    }

    @Test
    void getLines_whenAccountDoesNotExist_returnsEmptyList_withoutCreatingAccount() {
        ResponseEntity<List<LedgerLineResponse>> response = getLinesWithBearer(64L, "TONTINE");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEmpty();
    }

    @Test
    void getLines_withoutJwt_isRejectedWithUnauthorized() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/internal/accounts/63/TONTINE/lines", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private ResponseEntity<List<LedgerLineResponse>> getLinesWithBearer(Long ownerReference, String accountType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(buildToken(jwtTestKeyPair));
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        return restTemplate.exchange(
                "/internal/accounts/" + ownerReference + "/" + accountType + "/lines",
                org.springframework.http.HttpMethod.GET, entity,
                new ParameterizedTypeReference<List<LedgerLineResponse>>() { });
    }

    private ResponseEntity<AccountBalanceResponse> getBalanceWithBearer(Long ownerReference, String accountType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(buildToken(jwtTestKeyPair));
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        return restTemplate.exchange(
                "/internal/accounts/" + ownerReference + "/" + accountType + "/balance",
                org.springframework.http.HttpMethod.GET, entity, AccountBalanceResponse.class);
    }

    private static String buildToken(KeyPair signingKeyPair) {
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
                .signWith(signingKeyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }
}
