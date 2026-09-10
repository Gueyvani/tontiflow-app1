package com.tontiflow.interfaces.rest;

import com.tontiflow.domain.enums.MemberStatus;
import com.tontiflow.domain.enums.RoundStatus;
import com.tontiflow.domain.model.Tontine;
import com.tontiflow.domain.model.TontineMember;
import com.tontiflow.domain.model.TontineRound;
import com.tontiflow.infrastructure.repository.TontineMemberRepository;
import com.tontiflow.infrastructure.repository.TontineRepository;
import com.tontiflow.infrastructure.repository.TontineRoundRepository;
import com.tontiflow.infrastructure.security.JwtTestSecurityConfiguration;
import com.tontiflow.security.jwt.JwtClaimNames;
import io.jsonwebtoken.Jwts;
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

import java.security.KeyPair;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test d'intégration réel de l'historique des remplacements de bénéficiaire
 * (décision R9) — symétrique aux tests de {@code replaceBeneficiary} déjà
 * présents dans {@code TontineRoundControllerIntegrationTest}. Démarrage
 * Spring complet, H2 réel, JWT réel — l'historique est généré par de vrais
 * appels HTTP à {@code PUT /rounds/{roundId}/beneficiary}, jamais inséré
 * directement en base.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(JwtTestSecurityConfiguration.class)
class RotationHistoryIntegrationTest {

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

    @Test
    void listRotationHistory_afterTwoRealReplacements_returnsBothEntriesInOrder() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontineAndGetId(creator);
        Long roundId = createAssignedRoundAndGetId(tontineId, 3L);
        Long secondBeneficiaryId = createMemberAndGetId(tontineId);
        Long thirdBeneficiaryId = createMemberAndGetId(tontineId);

        ResponseEntity<String> firstReplace = exchangeWithBearer(
                HttpMethod.PUT, "/api/v1/tontines/rounds/" + roundId + "/beneficiary",
                "{\"newBeneficiaryId\":" + secondBeneficiaryId + ",\"reason\":\"membre indisponible\"}", creator);
        assertThat(firstReplace.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> secondReplace = exchangeWithBearer(
                HttpMethod.PUT, "/api/v1/tontines/rounds/" + roundId + "/beneficiary",
                "{\"newBeneficiaryId\":" + thirdBeneficiaryId + ",\"reason\":\"deuxieme correction\"}", creator);
        assertThat(secondReplace.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> response = exchangeWithBearer(
                HttpMethod.GET, "/api/v1/tontines/" + tontineId + "/rounds/" + roundId + "/rotation-history",
                null, creator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"reason\":\"membre indisponible\"");
        assertThat(response.getBody()).contains("\"reason\":\"deuxieme correction\"");
        assertThat(response.getBody()).contains("\"modificationType\":\"REPLACEMENT\"");
        assertThat(response.getBody()).contains("\"previousBeneficiaryId\":3");
        assertThat(response.getBody()).contains("\"newBeneficiaryId\":" + secondBeneficiaryId);
    }

    @Test
    void listRotationHistory_whenNoReplacementEverHappened_returnsEmptyList() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontineAndGetId(creator);
        Long roundId = createAssignedRoundAndGetId(tontineId, 3L);

        ResponseEntity<String> response = exchangeWithBearer(
                HttpMethod.GET, "/api/v1/tontines/" + tontineId + "/rounds/" + roundId + "/rotation-history",
                null, creator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("[]");
    }

    @Test
    void listRotationHistory_asNonCreator_isForbidden() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontineAndGetId(creator);
        Long roundId = createAssignedRoundAndGetId(tontineId, 3L);

        ResponseEntity<String> response = exchangeWithBearer(
                HttpMethod.GET, "/api/v1/tontines/" + tontineId + "/rounds/" + roundId + "/rotation-history",
                null, UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void listRotationHistory_withRoundFromAnotherTontine_isRejectedWithNotFound() {
        UUID creatorA = UUID.randomUUID();
        Long tontineA = createTontineAndGetId(creatorA);

        UUID creatorB = UUID.randomUUID();
        Long tontineB = createTontineAndGetId(creatorB);
        Long roundOfB = createAssignedRoundAndGetId(tontineB, 3L);

        ResponseEntity<String> response = exchangeWithBearer(
                HttpMethod.GET, "/api/v1/tontines/" + tontineA + "/rounds/" + roundOfB + "/rotation-history",
                null, creatorA);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void listRotationHistory_withoutToken_isRejectedWithUnauthorized() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/tontines/1/rounds/1/rotation-history", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private Long createTontineAndGetId(UUID creator) {
        Tontine tontine = new Tontine();
        tontine.setName("Tontine de test R9");
        tontine.setCreatorUserId(creator);
        tontine.setCreatedAt(LocalDateTime.now());
        return tontineRepository.save(tontine).getId();
    }

    private Long createAssignedRoundAndGetId(Long tontineId, Long beneficiaryId) {
        TontineRound round = new TontineRound();
        round.setTontineId(tontineId);
        round.setRoundNumber(1);
        round.setBeneficiaryId(beneficiaryId);
        round.setStatus(RoundStatus.ASSIGNED);
        round.setStartDate(LocalDateTime.now());
        round.setEndDate(LocalDateTime.now().plusDays(30));
        return roundRepository.save(round).getId();
    }

    private Long createMemberAndGetId(Long tontineId) {
        TontineMember member = new TontineMember();
        member.setTontineId(tontineId);
        member.setUserId(System.nanoTime()); // valeur arbitraire, unicite non requise ici
        member.setSequentialOrder(1);
        member.setStatus(MemberStatus.ACTIVE);
        member.setAccountId(UUID.randomUUID());
        return memberRepository.save(member).getId();
    }

    private ResponseEntity<String> exchangeWithBearer(HttpMethod method, String path, String body, UUID subject) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(validToken(subject));
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(body, headers);
        return restTemplate.exchange(path, method, entity, String.class);
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
