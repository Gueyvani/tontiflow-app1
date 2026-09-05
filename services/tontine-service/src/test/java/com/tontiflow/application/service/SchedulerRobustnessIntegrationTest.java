package com.tontiflow.application.service;

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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PHASE E — Étape 6 (cas configuration absente, RoundCompletionScheduler
 * réel) et Étape 10 (OrphanedCompletedRoundRetryScheduler en HTTP+DB réel,
 * scénarios non couverts par les tests unitaires mockés de Phase C).
 * Aucun mock de repository — vrai Spring, vrai H2, vrais beans.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(JwtTestSecurityConfiguration.class)
class SchedulerRobustnessIntegrationTest {

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
    private BlockedRoundRetryScheduler blockedRoundRetryScheduler;
    @Autowired
    private OrphanedCompletedRoundRetryScheduler orphanedCompletedRoundRetryScheduler;

    // ------------------------------------------------------------------
    // Étape 6 (Phase E) puis décision P1 : configuration absente lors de la
    // complétion d'un round expiré. Comportement remplacé : le round bascule
    // vers BLOCKED (plus de rollback ni de retry silencieux à 60s) et n'est
    // repris que par BlockedRoundRetryScheduler, une fois la configuration
    // corrigée.
    // ------------------------------------------------------------------
    @Test
    void completion_whenConfigMissing_blocksRoundThenResumesOnceConfigCorrected() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontine(creator);
        // Volontairement AUCUNE TontineConfig sauvegardee pour cette tontine.
        TontineRound round = saveRound(tontineId, 1, RoundStatus.ASSIGNED, 1L);
        round.setEndDate(LocalDateTime.now().minusMinutes(1));
        roundRepository.save(round);
        Long roundId = round.getId();

        // Premier passage du vrai scheduler.
        roundCompletionScheduler.completeExpiredRounds();

        TontineRound afterFirstPass = roundRepository.findById(roundId).orElseThrow();
        assertThat(afterFirstPass.getStatus()).isEqualTo(RoundStatus.BLOCKED); // ni ASSIGNED (rollback), ni COMPLETED
        assertThat(roundRepository.findByTontineId(tontineId)).hasSize(1); // aucun round suivant cree

        // Deuxieme passage du scheduler de completion normal : le round n'est
        // plus ASSIGNED, donc plus jamais reselectionne par ce job - fin du
        // retry silencieux a 60s (comportement remplace par la decision P1).
        roundCompletionScheduler.completeExpiredRounds();

        TontineRound afterSecondPass = roundRepository.findById(roundId).orElseThrow();
        assertThat(afterSecondPass.getStatus()).isEqualTo(RoundStatus.BLOCKED); // inchange, toujours bloque
        assertThat(roundRepository.findByTontineId(tontineId)).hasSize(1);

        // BlockedRoundRetryScheduler, tant que la configuration reste absente :
        // reste BLOCKED, aucune creation partielle.
        blockedRoundRetryScheduler.retryBlockedRounds();

        TontineRound stillBlocked = roundRepository.findById(roundId).orElseThrow();
        assertThat(stillBlocked.getStatus()).isEqualTo(RoundStatus.BLOCKED);
        assertThat(roundRepository.findByTontineId(tontineId)).hasSize(1);

        // Correction de la configuration, puis reprise explicite.
        saveConfig(tontineId);
        blockedRoundRetryScheduler.retryBlockedRounds();

        TontineRound afterRetry = roundRepository.findById(roundId).orElseThrow();
        assertThat(afterRetry.getStatus()).isEqualTo(RoundStatus.COMPLETED);
        List<TontineRound> roundsAfterRetry = roundRepository.findByTontineId(tontineId);
        assertThat(roundsAfterRetry).hasSize(2); // round suivant cree, aucune duplication
        TontineRound nextRound = roundsAfterRetry.stream()
                .filter(r -> r.getRoundNumber() == 2).findFirst().orElseThrow();
        assertThat(nextRound.getStatus()).isEqualTo(RoundStatus.PLANNED);
    }

    // ------------------------------------------------------------------
    // Étape 10 : OrphanedCompletedRoundRetryScheduler, scénarios réels HTTP+DB
    // ------------------------------------------------------------------
    @Test
    void orphanedRetry_realScheduler_createsNextRound_whenLastRoundCompletedAndNoPlanned() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontine(creator);
        saveConfig(tontineId);
        saveRound(tontineId, 1, RoundStatus.COMPLETED, 1L);

        orphanedCompletedRoundRetryScheduler.retryOrphanedCompletedRounds();

        List<TontineRound> rounds = roundRepository.findByTontineId(tontineId);
        assertThat(rounds).hasSize(2);
        TontineRound round2 = rounds.stream().filter(r -> r.getRoundNumber() == 2).findFirst().orElseThrow();
        assertThat(round2.getStatus()).isEqualTo(RoundStatus.PLANNED);
        assertThat(round2.getBeneficiaryId()).isNull();
    }

    @Test
    void orphanedRetry_realScheduler_doesNothing_whenPlannedAlreadyExists() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontine(creator);
        saveConfig(tontineId);
        saveRound(tontineId, 1, RoundStatus.COMPLETED, 1L);
        saveRound(tontineId, 2, RoundStatus.PLANNED, null);

        orphanedCompletedRoundRetryScheduler.retryOrphanedCompletedRounds();

        List<TontineRound> rounds = roundRepository.findByTontineId(tontineId);
        assertThat(rounds).hasSize(2); // aucun round #3 cree - pas de faux positif
    }

    @Test
    void orphanedRetry_realScheduler_doesNothing_whenLastRoundNotCompleted() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontine(creator);
        saveConfig(tontineId);
        saveRound(tontineId, 1, RoundStatus.ASSIGNED, 1L);

        orphanedCompletedRoundRetryScheduler.retryOrphanedCompletedRounds();

        assertThat(roundRepository.findByTontineId(tontineId)).hasSize(1); // pas de faux positif sur ASSIGNED
    }

    @Test
    void orphanedRetry_realScheduler_isolatesMultipleTontines_andContinuesAfterOneFails() {
        UUID creatorA = UUID.randomUUID();
        UUID creatorB = UUID.randomUUID();

        // Tontine A : orpheline mais SANS configuration -> echouera.
        Long tontineA = createTontine(creatorA);
        saveRound(tontineA, 1, RoundStatus.COMPLETED, 1L);

        // Tontine B : orpheline avec configuration -> doit reussir malgre l'echec de A.
        Long tontineB = createTontine(creatorB);
        saveConfig(tontineB);
        saveRound(tontineB, 1, RoundStatus.COMPLETED, 2L);

        orphanedCompletedRoundRetryScheduler.retryOrphanedCompletedRounds();

        assertThat(roundRepository.findByTontineId(tontineA)).hasSize(1); // A reste bloquee (config absente)
        List<TontineRound> roundsB = roundRepository.findByTontineId(tontineB);
        assertThat(roundsB).hasSize(2); // B a bien recu son round suivant malgre l'echec de A
        assertThat(roundsB.stream().anyMatch(r -> r.getRoundNumber() == 2 && r.getStatus() == RoundStatus.PLANNED))
                .isTrue();
    }

    private Long createTontine(UUID creator) {
        Tontine tontine = new Tontine();
        tontine.setName("Robustesse scheduler");
        tontine.setCreatorUserId(creator);
        tontine.setCreatedAt(LocalDateTime.now());
        return tontineRepository.save(tontine).getId();
    }

    private void saveConfig(Long tontineId) {
        TontineConfig config = new TontineConfig();
        config.setTontineId(tontineId);
        config.setRotationType(RotationType.SEQUENTIAL);
        config.setContributionAmount(BigDecimal.valueOf(100));
        config.setContributionFrequency(ContributionFrequency.MONTHLY);
        config.setMaxMembers(10);
        configRepository.save(config);
    }

    private TontineRound saveRound(Long tontineId, int roundNumber, RoundStatus status, Long beneficiaryId) {
        TontineRound round = new TontineRound();
        round.setTontineId(tontineId);
        round.setRoundNumber(roundNumber);
        round.setBeneficiaryId(beneficiaryId);
        round.setStatus(status);
        round.setStartDate(LocalDateTime.now());
        round.setEndDate(LocalDateTime.now().plusDays(30));
        return roundRepository.save(round);
    }
}
