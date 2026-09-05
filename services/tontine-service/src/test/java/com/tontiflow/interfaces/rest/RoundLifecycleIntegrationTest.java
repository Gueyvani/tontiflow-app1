package com.tontiflow.interfaces.rest;

import com.tontiflow.application.service.RoundCompletionScheduler;
import com.tontiflow.application.service.SuspendedRoundRetryScheduler;
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

/**
 * Démontre réellement, avec Spring complet, H2 réel et les vrais beans
 * {@link RoundCompletionScheduler}/{@link SuspendedRoundRetryScheduler}
 * (invoqués directement, sans mock) :
 * <ul>
 *     <li>le cycle complet round #1 → ASSIGNED → COMPLETED → round #2 →
 *     ASSIGNED → COMPLETED → round #3, déclenché via {@code POST /tontines}
 *     réel puis persistance réelle à chaque étape ;</li>
 *     <li>la reprise automatique d'un round {@code SUSPENDED} (décision S1b)
 *     lorsqu'un membre éligible devient disponible.</li>
 * </ul>
 * Aucun mock ne remplace la persistance — comble le gap identifié en Phase B
 * (la chaîne complète n'était auparavant prouvée que par des tests unitaires
 * mockés).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(JwtTestSecurityConfiguration.class)
class RoundLifecycleIntegrationTest {

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

    @Autowired
    private RoundCompletionScheduler roundCompletionScheduler;

    @Autowired
    private SuspendedRoundRetryScheduler suspendedRoundRetryScheduler;

    @Test
    void fullCycle_roundOneThroughThree_withRealSchedulerAndRealPersistence() {
        UUID creator = UUID.randomUUID();

        // Chaîne complète réelle : POST /tontines crée Tontine + Config + Round #1.
        ResponseEntity<String> createResponse = exchangeWithBearer(
                HttpMethod.POST, "/api/v1/tontines",
                "{\"name\":\"Cycle complet\",\"contributionAmount\":100,\"contributionFrequency\":\"MONTHLY\","
                        + "\"maxMembers\":10,\"rotationType\":\"SEQUENTIAL\",\"nonCompliantBehavior\":\"POSTPONE\"}",
                creator);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Long tontineId = extractId(createResponse.getBody());

        // 3 membres eligibles : necessaire pour que rounds 1/2/3 soient tous
        // assignables (un membre deja beneficiaire redevient inelligible pour
        // les rounds suivants, cf. EligibilityEngine).
        for (long userId = 1; userId <= 3; userId++) {
            ResponseEntity<String> addMember = exchangeWithBearer(
                    HttpMethod.POST, "/api/v1/tontines/" + tontineId + "/members",
                    "{\"userId\":" + userId + ",\"sequentialOrder\":" + userId + "}", creator);
            assertThat(addMember.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        // --- ROUND #1 : verifie reellement la creation automatique (POST /tontines) ---
        List<TontineRound> roundsAfterCreation = roundRepository.findByTontineId(tontineId);
        assertThat(roundsAfterCreation).hasSize(1);
        TontineRound round1 = roundsAfterCreation.get(0);
        assertThat(round1.getRoundNumber()).isEqualTo(1);
        assertThat(round1.getStatus()).isEqualTo(RoundStatus.PLANNED);
        assertThat(round1.getBeneficiaryId()).isNull();
        assertThat(round1.getAmount()).isEqualByComparingTo(BigDecimal.valueOf(100));

        assignAndExpireAndComplete(tontineId, round1.getId(), creator);

        // --- ROUND #2 : cree automatiquement par le vrai scheduler ---
        TontineRound round2 = findRoundByNumber(tontineId, 2);
        assertThat(round2.getStatus()).isEqualTo(RoundStatus.PLANNED);
        assertThat(round2.getBeneficiaryId()).isNull();
        assertThat(round2.getAmount()).isEqualByComparingTo(BigDecimal.valueOf(100));

        assignAndExpireAndComplete(tontineId, round2.getId(), creator);

        // --- ROUND #3 : cree automatiquement apres completion du round #2 ---
        TontineRound round3 = findRoundByNumber(tontineId, 3);
        assertThat(round3.getStatus()).isEqualTo(RoundStatus.PLANNED);
        assertThat(round3.getBeneficiaryId()).isNull();

        // Round #1 et #2 restent bien COMPLETED (pas de regression retroactive).
        assertThat(roundRepository.findById(round1.getId()).orElseThrow().getStatus())
                .isEqualTo(RoundStatus.COMPLETED);
        assertThat(roundRepository.findById(round2.getId()).orElseThrow().getStatus())
                .isEqualTo(RoundStatus.COMPLETED);
    }

    @Test
    void suspendedRound_recoversAutomaticallyViaRealScheduler_whenMemberBecomesEligible() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontineDirectly(creator);

        TontineConfig config = new TontineConfig();
        config.setTontineId(tontineId);
        config.setRotationType(RotationType.SEQUENTIAL);
        config.setContributionAmount(BigDecimal.valueOf(100));
        config.setContributionFrequency(ContributionFrequency.MONTHLY);
        config.setMaxMembers(10);
        configRepository.save(config);

        // Round deja SUSPENDED - aucun membre eligible au moment de la creation.
        TontineRound round = new TontineRound();
        round.setTontineId(tontineId);
        round.setRoundNumber(1);
        round.setStatus(RoundStatus.SUSPENDED);
        round.setStartDate(LocalDateTime.now());
        round.setEndDate(LocalDateTime.now().plusDays(30));
        round = roundRepository.save(round);
        Long roundId = round.getId();

        // Un membre eligible devient disponible.
        TontineMember member = new TontineMember();
        member.setTontineId(tontineId);
        member.setUserId(42L);
        member.setSequentialOrder(1);
        member = memberRepository.save(member);
        Long memberId = member.getId();

        // Vrai bean Spring, invocation directe (equivalent a un declenchement
        // planifie reel) - aucune persistance mockee.
        suspendedRoundRetryScheduler.retrySuspendedRounds();

        TontineRound afterRetry = roundRepository.findById(roundId).orElseThrow();
        assertThat(afterRetry.getStatus()).isEqualTo(RoundStatus.ASSIGNED);
        assertThat(afterRetry.getBeneficiaryId()).isEqualTo(memberId);
    }

    @Test
    void suspendedRound_staysSuspendedViaRealScheduler_whenStillNoEligibleMember() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontineDirectly(creator);

        TontineConfig config = new TontineConfig();
        config.setTontineId(tontineId);
        config.setRotationType(RotationType.SEQUENTIAL);
        config.setContributionAmount(BigDecimal.valueOf(100));
        config.setContributionFrequency(ContributionFrequency.MONTHLY);
        config.setMaxMembers(10);
        configRepository.save(config);

        TontineRound round = new TontineRound();
        round.setTontineId(tontineId);
        round.setRoundNumber(1);
        round.setStatus(RoundStatus.SUSPENDED);
        round.setStartDate(LocalDateTime.now());
        round.setEndDate(LocalDateTime.now().plusDays(30));
        round = roundRepository.save(round);
        Long roundId = round.getId();
        // Aucun membre ajoute.

        suspendedRoundRetryScheduler.retrySuspendedRounds();

        TontineRound afterRetry = roundRepository.findById(roundId).orElseThrow();
        assertThat(afterRetry.getStatus()).isEqualTo(RoundStatus.SUSPENDED);
        assertThat(afterRetry.getBeneficiaryId()).isNull();
    }

    /**
     * Assigne un bénéficiaire réel (HTTP), force l'expiration du round via
     * la base (équivalent à « attendre » réellement l'échéance), puis
     * invoque le vrai bean {@link RoundCompletionScheduler} — pas de mock.
     */
    private void assignAndExpireAndComplete(Long tontineId, Long roundId, UUID creator) {
        ResponseEntity<String> assign = exchangeWithBearer(
                HttpMethod.POST, "/api/v1/tontines/" + tontineId + "/rounds/" + roundId + "/assign-beneficiary",
                null, creator);
        assertThat(assign.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(assign.getBody()).contains("\"status\":\"ASSIGNED\"");

        TontineRound assigned = roundRepository.findById(roundId).orElseThrow();
        assigned.setEndDate(LocalDateTime.now().minusMinutes(1));
        roundRepository.save(assigned);

        roundCompletionScheduler.completeExpiredRounds();

        TontineRound completed = roundRepository.findById(roundId).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(RoundStatus.COMPLETED);
    }

    private TontineRound findRoundByNumber(Long tontineId, int roundNumber) {
        return roundRepository.findByTontineId(tontineId).stream()
                .filter(r -> r.getRoundNumber() == roundNumber)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Round #" + roundNumber + " introuvable pour la tontine " + tontineId));
    }

    private Long createTontineDirectly(UUID creator) {
        Tontine tontine = new Tontine();
        tontine.setName("Tontine SUSPENDED");
        tontine.setCreatorUserId(creator);
        tontine.setCreatedAt(LocalDateTime.now());
        return tontineRepository.save(tontine).getId();
    }

    private static Long extractId(String body) {
        String idStr = body.substring(body.indexOf("\"id\":") + 5, body.indexOf(",", body.indexOf("\"id\":")));
        return Long.parseLong(idStr.trim());
    }

    private ResponseEntity<String> exchangeWithBearer(HttpMethod method, String path, String body, UUID subject) {
        String token = validToken(subject);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
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
                .claim(JwtClaimNames.USERNAME, "alice")
                .claim(JwtClaimNames.EMAIL, "alice@tontiflow.test")
                .claim(JwtClaimNames.ROLES, List.copyOf(Set.of("ROLE_USER")))
                .claim(JwtClaimNames.PERMISSIONS, List.copyOf(Set.<String>of()))
                .signWith(jwtTestKeyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }
}
