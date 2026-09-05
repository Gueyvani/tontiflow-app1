package com.tontiflow.interfaces.rest;

import com.tontiflow.domain.enums.ContributionFrequency;
import com.tontiflow.domain.enums.RotationType;
import com.tontiflow.domain.enums.RoundStatus;
import com.tontiflow.domain.model.Tontine;
import com.tontiflow.domain.model.TontineConfig;
import com.tontiflow.domain.model.TontineMember;
import com.tontiflow.domain.model.TontineRound;
import com.tontiflow.infrastructure.repository.TontineConfigRepository;
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

/**
 * Test d'intégration réel de {@link TontineRoundController} : démarrage
 * Spring complet, H2 réel (schéma généré depuis les entités JPA, profil
 * {@code test}), authentification JWT réelle — pas de mock de la sécurité.
 *
 * <p>Chaque scénario d'accès autorisé crée une véritable {@link Tontine}
 * avec un {@code creatorUserId} connu, et authentifie l'appelant avec ce
 * même UUID — le contrôle d'accès au niveau ressource (créateur seul,
 * fail-closed) exige cette relation réelle.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(JwtTestSecurityConfiguration.class)
class TontineRoundControllerIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private KeyPair jwtTestKeyPair;

    @Autowired
    private TontineRepository tontineRepository;

    @Autowired
    private TontineConfigRepository configRepository;

    @Autowired
    private TontineMemberRepository memberRepository;

    @Autowired
    private TontineRoundRepository roundRepository;

    @Test
    void assignBeneficiary_withoutToken_isRejectedWithUnauthorized() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/tontines/1/rounds/1/assign-beneficiary", null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void assignBeneficiary_withValidToken_selectsEligibleMemberAndPersists() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontineAndGetId(creator);

        TontineConfig config = new TontineConfig();
        config.setTontineId(tontineId);
        config.setRotationType(RotationType.SEQUENTIAL);
        config.setContributionAmount(BigDecimal.valueOf(100));
        config.setContributionFrequency(ContributionFrequency.MONTHLY);
        config.setMaxMembers(10);
        configRepository.save(config);

        TontineMember member = new TontineMember();
        member.setTontineId(tontineId);
        member.setUserId(1L);
        member.setSequentialOrder(1);
        memberRepository.save(member);

        TontineRound round = new TontineRound();
        round.setTontineId(tontineId);
        round.setRoundNumber(1);
        round.setAmount(BigDecimal.valueOf(1000));
        round.setStatus(RoundStatus.PLANNED);
        round.setStartDate(LocalDateTime.now());
        round.setEndDate(LocalDateTime.now().plusDays(30));
        round = roundRepository.save(round);

        ResponseEntity<String> response = exchangeWithBearer(
                HttpMethod.POST, "/api/v1/tontines/" + tontineId + "/rounds/" + round.getId() + "/assign-beneficiary",
                null, creator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"ASSIGNED\"");
        assertThat(response.getBody()).contains("\"beneficiaryId\":" + member.getId());
    }

    @Test
    void assignBeneficiary_forUnknownRound_returnsNotFound() {
        ResponseEntity<String> response = exchangeWithBearer(
                HttpMethod.POST, "/api/v1/tontines/1/rounds/999999/assign-beneficiary", null, UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void assignBeneficiary_asNonCreator_isForbidden() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontineAndGetId(creator);

        TontineRound round = new TontineRound();
        round.setTontineId(tontineId);
        round.setRoundNumber(1);
        round.setStatus(RoundStatus.PLANNED);
        round.setStartDate(LocalDateTime.now());
        round.setEndDate(LocalDateTime.now().plusDays(30));
        round = roundRepository.save(round);
        Long roundId = round.getId();

        ResponseEntity<String> response = exchangeWithBearer(
                HttpMethod.POST, "/api/v1/tontines/" + tontineId + "/rounds/" + roundId + "/assign-beneficiary",
                null, UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Le round ne doit pas avoir ete modifie par la tentative refusee.
        TontineRound unchanged = roundRepository.findById(roundId).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(RoundStatus.PLANNED);
        assertThat(unchanged.getBeneficiaryId()).isNull();
    }

    @Test
    void assignBeneficiary_whenTontineIdInPathDoesNotMatchRoundsTontine_returnsNotFoundAndDoesNotWriteCrossTenant() {
        // Correctif R1 : round reel appartenant a la tontine A, mais l'appelant
        // (createur reel de A) indique l'id de la tontine B dans le chemin.
        // Doit etre rejete AVANT toute lecture/ecriture des donnees de B.
        UUID creatorA = UUID.randomUUID();
        Long tontineIdA = createTontineAndGetId(creatorA);
        Long tontineIdB = createTontineAndGetId(UUID.randomUUID());

        TontineConfig configB = new TontineConfig();
        configB.setTontineId(tontineIdB);
        configB.setRotationType(RotationType.SEQUENTIAL);
        configB.setContributionAmount(BigDecimal.valueOf(100));
        configB.setContributionFrequency(ContributionFrequency.MONTHLY);
        configB.setMaxMembers(10);
        configRepository.save(configB);

        TontineMember memberOfB = new TontineMember();
        memberOfB.setTontineId(tontineIdB);
        memberOfB.setUserId(42L);
        memberOfB.setSequentialOrder(1);
        Long memberOfBId = memberRepository.save(memberOfB).getId();

        TontineRound roundOfA = new TontineRound();
        roundOfA.setTontineId(tontineIdA);
        roundOfA.setRoundNumber(1);
        roundOfA.setStatus(RoundStatus.PLANNED);
        roundOfA.setStartDate(LocalDateTime.now());
        roundOfA.setEndDate(LocalDateTime.now().plusDays(30));
        roundOfA = roundRepository.save(roundOfA);
        Long roundId = roundOfA.getId();

        ResponseEntity<String> response = exchangeWithBearer(
                HttpMethod.POST,
                "/api/v1/tontines/" + tontineIdB + "/rounds/" + roundId + "/assign-beneficiary",
                null, creatorA);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Le round de A n'a pas ete modifie.
        TontineRound unchanged = roundRepository.findById(roundId).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(RoundStatus.PLANNED);
        assertThat(unchanged.getBeneficiaryId()).isNull();
        assertThat(unchanged.getTontineId()).isEqualTo(tontineIdA);

        // Aucune ecriture cross-tenant : le membre de B n'a ete assigne a aucun round.
        List<TontineRound> roundsOfB = roundRepository.findByTontineId(tontineIdB);
        assertThat(roundsOfB).noneMatch(r -> memberOfBId.equals(r.getBeneficiaryId()));
    }

    @Test
    void listRounds_asCreator_returnsAllRounds() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontineAndGetId(creator);
        saveRound(tontineId, 1, RoundStatus.COMPLETED);
        saveRound(tontineId, 2, RoundStatus.PLANNED);

        ResponseEntity<String> response = exchangeWithBearer(
                HttpMethod.GET, "/api/v1/tontines/" + tontineId + "/rounds", null, creator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"roundNumber\":1").contains("\"roundNumber\":2");
    }

    @Test
    void listRounds_asNonCreator_isForbidden() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontineAndGetId(creator);

        ResponseEntity<String> response = exchangeWithBearer(
                HttpMethod.GET, "/api/v1/tontines/" + tontineId + "/rounds", null, UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void getRound_asCreator_returnsRound() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontineAndGetId(creator);
        TontineRound round = saveRound(tontineId, 1, RoundStatus.PLANNED);

        ResponseEntity<String> response = exchangeWithBearer(
                HttpMethod.GET, "/api/v1/tontines/" + tontineId + "/rounds/" + round.getId(), null, creator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"roundNumber\":1");
    }

    @Test
    void getRound_forRoundOfDifferentTontine_returnsNotFound() {
        UUID creator = UUID.randomUUID();
        Long tontineIdA = createTontineAndGetId(creator);
        Long tontineIdB = createTontineAndGetId(creator);
        TontineRound roundOfB = saveRound(tontineIdB, 1, RoundStatus.PLANNED);

        ResponseEntity<String> response = exchangeWithBearer(
                HttpMethod.GET, "/api/v1/tontines/" + tontineIdA + "/rounds/" + roundOfB.getId(), null, creator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getRound_asNonCreator_isForbidden() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontineAndGetId(creator);
        TontineRound round = saveRound(tontineId, 1, RoundStatus.PLANNED);

        ResponseEntity<String> response = exchangeWithBearer(
                HttpMethod.GET, "/api/v1/tontines/" + tontineId + "/rounds/" + round.getId(), null, UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void getCurrentRound_whenPlannedRoundExists_returnsIt() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontineAndGetId(creator);
        saveRound(tontineId, 1, RoundStatus.COMPLETED);
        saveRound(tontineId, 2, RoundStatus.PLANNED);

        ResponseEntity<String> response = exchangeWithBearer(
                HttpMethod.GET, "/api/v1/tontines/" + tontineId + "/rounds/current", null, creator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"roundNumber\":2").contains("\"status\":\"PLANNED\"");
    }

    @Test
    void getCurrentRound_whenNoActiveRound_returnsNotFound() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontineAndGetId(creator);
        saveRound(tontineId, 1, RoundStatus.COMPLETED);

        ResponseEntity<String> response = exchangeWithBearer(
                HttpMethod.GET, "/api/v1/tontines/" + tontineId + "/rounds/current", null, creator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getCurrentRound_asNonCreator_isForbidden() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontineAndGetId(creator);
        saveRound(tontineId, 1, RoundStatus.PLANNED);

        ResponseEntity<String> response = exchangeWithBearer(
                HttpMethod.GET, "/api/v1/tontines/" + tontineId + "/rounds/current", null, UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void assignBeneficiary_onSuspendedRound_withNowEligibleMember_transitionsToAssigned() {
        // S4a : un round SUSPENDED (aucun membre eligible au moment de la
        // premiere tentative) doit retenter l'eligibilite lors d'un nouvel
        // appel assign-beneficiary - si un membre est desormais eligible,
        // le round passe a ASSIGNED (comportement actuel conserve).
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontineAndGetId(creator);

        TontineConfig config = new TontineConfig();
        config.setTontineId(tontineId);
        config.setRotationType(RotationType.SEQUENTIAL);
        config.setContributionAmount(BigDecimal.valueOf(100));
        config.setContributionFrequency(ContributionFrequency.MONTHLY);
        config.setMaxMembers(10);
        configRepository.save(config);

        // Round deja SUSPENDED (aucun membre au moment de la premiere tentative).
        TontineRound round = saveRound(tontineId, 1, RoundStatus.SUSPENDED);

        // Un membre eligible est desormais present.
        TontineMember member = new TontineMember();
        member.setTontineId(tontineId);
        member.setUserId(1L);
        member.setSequentialOrder(1);
        member = memberRepository.save(member);

        ResponseEntity<String> response = exchangeWithBearer(
                HttpMethod.POST, "/api/v1/tontines/" + tontineId + "/rounds/" + round.getId() + "/assign-beneficiary",
                null, creator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"ASSIGNED\"");
        assertThat(response.getBody()).contains("\"beneficiaryId\":" + member.getId());
    }

    @Test
    void assignBeneficiary_onSuspendedRound_withNoEligibleMember_remainsSuspended() {
        // S4a : si aucun membre n'est toujours eligible, le round retente
        // l'eligibilite mais reste SUSPENDED (aucun round suivant ne doit
        // etre cree tant que S1b ne permet pas une reprise).
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontineAndGetId(creator);

        TontineConfig config = new TontineConfig();
        config.setTontineId(tontineId);
        config.setRotationType(RotationType.SEQUENTIAL);
        config.setContributionAmount(BigDecimal.valueOf(100));
        config.setContributionFrequency(ContributionFrequency.MONTHLY);
        config.setMaxMembers(10);
        configRepository.save(config);

        TontineRound round = saveRound(tontineId, 1, RoundStatus.SUSPENDED);
        // Aucun membre ajoute.

        ResponseEntity<String> response = exchangeWithBearer(
                HttpMethod.POST, "/api/v1/tontines/" + tontineId + "/rounds/" + round.getId() + "/assign-beneficiary",
                null, creator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"SUSPENDED\"");

        TontineRound unchanged = roundRepository.findById(round.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(RoundStatus.SUSPENDED);
        assertThat(unchanged.getBeneficiaryId()).isNull();
        // Aucun round suivant ne doit avoir ete cree.
        assertThat(roundRepository.findByTontineId(tontineId)).hasSize(1);
    }

    private TontineRound saveRound(Long tontineId, int roundNumber, RoundStatus status) {
        TontineRound round = new TontineRound();
        round.setTontineId(tontineId);
        round.setRoundNumber(roundNumber);
        round.setStatus(status);
        round.setStartDate(LocalDateTime.now());
        round.setEndDate(LocalDateTime.now().plusDays(30));
        return roundRepository.save(round);
    }

    @Test
    void replaceBeneficiary_withoutToken_isRejectedWithUnauthorized() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>("{\"newBeneficiaryId\":1,\"reason\":\"test\"}", headers);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/tontines/rounds/1/beneficiary", HttpMethod.PUT, entity, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void replaceBeneficiary_withValidToken_updatesRoundAndRecordsActorFromJwt() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontineAndGetId(creator);

        TontineRound round = new TontineRound();
        round.setTontineId(tontineId);
        round.setRoundNumber(1);
        round.setBeneficiaryId(3L);
        round.setStatus(RoundStatus.ASSIGNED);
        round.setStartDate(LocalDateTime.now());
        round.setEndDate(LocalDateTime.now().plusDays(30));
        round = roundRepository.save(round);

        // Regle B (Phase F3) : le nouveau beneficiaire doit desormais
        // appartenir reellement a la tontine du round.
        TontineMember newBeneficiary = new TontineMember();
        newBeneficiary.setTontineId(tontineId);
        newBeneficiary.setUserId(99L);
        newBeneficiary.setSequentialOrder(1);
        Long newBeneficiaryId = memberRepository.save(newBeneficiary).getId();

        ResponseEntity<String> response = exchangeWithBearer(
                HttpMethod.PUT, "/api/v1/tontines/rounds/" + round.getId() + "/beneficiary",
                "{\"newBeneficiaryId\":" + newBeneficiaryId + ",\"reason\":\"membre exclu\"}", creator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"beneficiaryId\":" + newBeneficiaryId);
    }

    @Test
    void replaceBeneficiary_onCompletedRound_isRejectedWithConflict_andRoundUnchanged() {
        // Regle A (Phase F3), HTTP+DB reel.
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontineAndGetId(creator);

        TontineMember member = new TontineMember();
        member.setTontineId(tontineId);
        member.setUserId(1L);
        member.setSequentialOrder(1);
        Long memberId = memberRepository.save(member).getId();

        TontineRound round = new TontineRound();
        round.setTontineId(tontineId);
        round.setRoundNumber(1);
        round.setBeneficiaryId(3L);
        round.setStatus(RoundStatus.COMPLETED);
        round.setStartDate(LocalDateTime.now());
        round.setEndDate(LocalDateTime.now().plusDays(30));
        round = roundRepository.save(round);
        Long roundId = round.getId();

        ResponseEntity<String> response = exchangeWithBearer(
                HttpMethod.PUT, "/api/v1/tontines/rounds/" + roundId + "/beneficiary",
                "{\"newBeneficiaryId\":" + memberId + ",\"reason\":\"tentative sur round termine\"}", creator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // Verification apres rollback reel : aucune ecriture partielle.
        TontineRound unchanged = roundRepository.findById(roundId).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(RoundStatus.COMPLETED);
        assertThat(unchanged.getBeneficiaryId()).isEqualTo(3L);
    }

    @Test
    void replaceBeneficiary_withBeneficiaryFromAnotherTontine_isRejectedWithNotFound_andRoundUnchanged() {
        // Regle B (Phase F3), HTTP+DB reel.
        UUID creatorA = UUID.randomUUID();
        Long tontineIdA = createTontineAndGetId(creatorA);
        TontineRound roundOfA = new TontineRound();
        roundOfA.setTontineId(tontineIdA);
        roundOfA.setRoundNumber(1);
        roundOfA.setBeneficiaryId(3L);
        roundOfA.setStatus(RoundStatus.ASSIGNED);
        roundOfA.setStartDate(LocalDateTime.now());
        roundOfA.setEndDate(LocalDateTime.now().plusDays(30));
        roundOfA = roundRepository.save(roundOfA);
        Long roundOfAId = roundOfA.getId();

        Long tontineIdB = createTontineAndGetId(UUID.randomUUID());
        TontineMember memberOfB = new TontineMember();
        memberOfB.setTontineId(tontineIdB);
        memberOfB.setUserId(42L);
        memberOfB.setSequentialOrder(1);
        Long memberOfBId = memberRepository.save(memberOfB).getId();

        ResponseEntity<String> response = exchangeWithBearer(
                HttpMethod.PUT, "/api/v1/tontines/rounds/" + roundOfAId + "/beneficiary",
                "{\"newBeneficiaryId\":" + memberOfBId + ",\"reason\":\"tentative cross-tenant\"}", creatorA);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        TontineRound unchanged = roundRepository.findById(roundOfAId).orElseThrow();
        assertThat(unchanged.getBeneficiaryId()).isEqualTo(3L); // inchange, pas memberOfBId
        assertThat(unchanged.getStatus()).isEqualTo(RoundStatus.ASSIGNED);
    }

    @Test
    void replaceBeneficiary_asNonCreator_isForbidden() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontineAndGetId(creator);

        TontineRound round = new TontineRound();
        round.setTontineId(tontineId);
        round.setRoundNumber(1);
        round.setBeneficiaryId(3L);
        round.setStatus(RoundStatus.ASSIGNED);
        round.setStartDate(LocalDateTime.now());
        round.setEndDate(LocalDateTime.now().plusDays(30));
        round = roundRepository.save(round);
        Long roundId = round.getId();

        ResponseEntity<String> response = exchangeWithBearer(
                HttpMethod.PUT, "/api/v1/tontines/rounds/" + roundId + "/beneficiary",
                "{\"newBeneficiaryId\":9,\"reason\":\"membre exclu\"}", UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Le beneficiaire ne doit pas avoir ete modifie par la tentative refusee.
        TontineRound unchanged = roundRepository.findById(roundId).orElseThrow();
        assertThat(unchanged.getBeneficiaryId()).isEqualTo(3L);
    }

    private Long createTontineAndGetId(UUID creator) {
        Tontine tontine = new Tontine();
        tontine.setName("Tontine des collègues");
        tontine.setCreatorUserId(creator);
        tontine.setCreatedAt(LocalDateTime.now());
        return tontineRepository.save(tontine).getId();
    }

    private ResponseEntity<String> exchangeWithBearer(HttpMethod method, String path, String body, UUID subject) {
        String token = validToken(subject);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
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
                .claim(JwtClaimNames.USERNAME, "alice")
                .claim(JwtClaimNames.EMAIL, "alice@tontiflow.test")
                .claim(JwtClaimNames.ROLES, List.copyOf(Set.of("ROLE_USER")))
                .claim(JwtClaimNames.PERMISSIONS, List.copyOf(Set.<String>of()))
                .signWith(jwtTestKeyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }
}
