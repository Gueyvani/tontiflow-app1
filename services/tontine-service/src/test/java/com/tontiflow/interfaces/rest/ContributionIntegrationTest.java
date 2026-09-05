package com.tontiflow.interfaces.rest;

import com.tontiflow.domain.enums.RoundStatus;
import com.tontiflow.domain.model.Tontine;
import com.tontiflow.domain.model.TontineMember;
import com.tontiflow.domain.model.TontineRound;
import com.tontiflow.infrastructure.client.FinancialServiceClient;
import com.tontiflow.infrastructure.repository.TontineMemberRepository;
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
 * Test d'intégration réel du flux de contribution (décision R3) : démarrage
 * Spring complet, H2 réel, authentification JWT réelle — même patron que
 * {@code TontineRoundControllerIntegrationTest}. {@link FinancialServiceClient}
 * est mocké ({@code @MockBean}) : ce test prouve l'autorisation/validation
 * côté tontine-service ; le comportement réel du Ledger est prouvé
 * séparément côté financial-service (§31, limite documentée honnêtement).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(JwtTestSecurityConfiguration.class)
class ContributionIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private KeyPair jwtTestKeyPair;
    @Autowired
    private TontineRepository tontineRepository;
    @Autowired
    private TontineMemberRepository memberRepository;
    @Autowired
    private TontineRoundRepository roundRepository;
    @MockBean
    private FinancialServiceClient financialServiceClient;

    @Test
    void recordContribution_withoutToken_isRejectedWithUnauthorized() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/tontines/1/rounds/1/contributions", jsonBody(1L), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(financialServiceClient, never()).recordContribution(any(), any(), any(), any(), anyString());
    }

    @Test
    void recordContribution_asCreator_withValidMemberAndRound_succeeds() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontineAndGetId(creator);
        Long memberId = createMemberAndGetId(tontineId);
        Long roundId = createRoundAndGetId(tontineId, new BigDecimal("5000.00"));

        ResponseEntity<String> response = exchangeWithBearer(
                "/api/v1/tontines/" + tontineId + "/rounds/" + roundId + "/contributions",
                jsonBody(memberId), creator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(financialServiceClient).recordContribution(
                eq(tontineId), eq(roundId), eq(memberId), eq(new BigDecimal("5000.00")), anyString());
    }

    // TEST 44 (§7/§44.1) : non-createur -> refus, aucune ecriture financiere.
    @Test
    void recordContribution_asNonCreator_isForbidden() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontineAndGetId(creator);
        Long memberId = createMemberAndGetId(tontineId);
        Long roundId = createRoundAndGetId(tontineId, new BigDecimal("5000.00"));

        ResponseEntity<String> response = exchangeWithBearer(
                "/api/v1/tontines/" + tontineId + "/rounds/" + roundId + "/contributions",
                jsonBody(memberId), UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(financialServiceClient, never()).recordContribution(any(), any(), any(), any(), anyString());
    }

    // TEST 44.3 (§44, troisieme test) : roundId appartient a Tontine B, mais
    // tontineId (chemin) = Tontine A -> refus, aucune ecriture.
    @Test
    void recordContribution_withRoundFromAnotherTontine_isRejected() {
        UUID creatorA = UUID.randomUUID();
        Long tontineA = createTontineAndGetId(creatorA);
        Long memberOfA = createMemberAndGetId(tontineA);

        UUID creatorB = UUID.randomUUID();
        Long tontineB = createTontineAndGetId(creatorB);
        Long roundOfB = createRoundAndGetId(tontineB, new BigDecimal("1000.00"));

        ResponseEntity<String> response = exchangeWithBearer(
                "/api/v1/tontines/" + tontineA + "/rounds/" + roundOfB + "/contributions",
                jsonBody(memberOfA), creatorA);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(financialServiceClient, never()).recordContribution(any(), any(), any(), any(), anyString());
    }

    // TEST 44.2 (§44, deuxieme test) : memberId appartient a Tontine B ->
    // refus, aucune ecriture, meme si l'appelant est bien createur de A.
    @Test
    void recordContribution_withMemberFromAnotherTontine_isRejected() {
        UUID creatorA = UUID.randomUUID();
        Long tontineA = createTontineAndGetId(creatorA);
        Long roundOfA = createRoundAndGetId(tontineA, new BigDecimal("1000.00"));

        UUID creatorB = UUID.randomUUID();
        Long tontineB = createTontineAndGetId(creatorB);
        Long memberOfB = createMemberAndGetId(tontineB);

        ResponseEntity<String> response = exchangeWithBearer(
                "/api/v1/tontines/" + tontineA + "/rounds/" + roundOfA + "/contributions",
                jsonBody(memberOfB), creatorA);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(financialServiceClient, never()).recordContribution(any(), any(), any(), any(), anyString());
    }

    private Long createTontineAndGetId(UUID creator) {
        Tontine tontine = new Tontine();
        tontine.setName("Tontine de test R3");
        tontine.setCreatorUserId(creator);
        tontine.setCreatedAt(LocalDateTime.now());
        return tontineRepository.save(tontine).getId();
    }

    private Long createMemberAndGetId(Long tontineId) {
        TontineMember member = new TontineMember();
        member.setTontineId(tontineId);
        member.setUserId(System.nanoTime()); // valeur arbitraire, non liee a un JWT (cf. rapport d'inspection)
        member.setSequentialOrder(1);
        return memberRepository.save(member).getId();
    }

    private Long createRoundAndGetId(Long tontineId, BigDecimal amount) {
        TontineRound round = new TontineRound();
        round.setTontineId(tontineId);
        round.setRoundNumber(1);
        round.setAmount(amount);
        round.setStatus(RoundStatus.PLANNED);
        round.setStartDate(LocalDateTime.now());
        round.setEndDate(LocalDateTime.now().plusDays(30));
        return roundRepository.save(round).getId();
    }

    private static String jsonBody(Long memberId) {
        return "{\"memberId\":" + memberId + "}";
    }

    private ResponseEntity<String> exchangeWithBearer(String path, String body, UUID subject) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(validToken(subject));
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(body, headers);
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
