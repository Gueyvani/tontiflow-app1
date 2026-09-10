package com.tontiflow.interfaces.rest;

import com.tontiflow.application.service.OrphanedCompletedRoundRetryScheduler;
import com.tontiflow.application.service.RoundCompletionScheduler;
import com.tontiflow.application.service.SuspendedRoundRetryScheduler;
import com.tontiflow.domain.enums.ContributionFrequency;
import com.tontiflow.domain.enums.MemberStatus;
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
 * Preuve d'idempotence par EXÉCUTIONS SÉQUENTIELLES explicites (≥3 appels
 * successifs sur le même vrai bean Spring, même base H2 réelle, aucun mock)
 * — complète la preuve de concurrence (2 threads simultanés) par une preuve
 * distincte : plusieurs passages du même job planifié, l'un après l'autre,
 * ne doivent jamais dupliquer d'écriture.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(JwtTestSecurityConfiguration.class)
class SchedulerIdempotenceIntegrationTest {

    @Autowired
    private TontineRepository tontineRepository;
    @Autowired
    private TontineConfigRepository configRepository;
    @Autowired
    private TontineMemberRepository memberRepository;
    @Autowired
    private TontineRoundRepository roundRepository;
    @Autowired
    private SuspendedRoundRetryScheduler suspendedRoundRetryScheduler;
    @Autowired
    private OrphanedCompletedRoundRetryScheduler orphanedCompletedRoundRetryScheduler;
    @Autowired
    private RoundCompletionScheduler roundCompletionScheduler;

    @Test
    void suspendedRetry_executedThreeTimesSequentially_yieldsExactlyOneAssignmentNoDuplication() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontine(creator);
        saveConfig(tontineId, RotationType.SEQUENTIAL, BigDecimal.valueOf(100), ContributionFrequency.MONTHLY);

        TontineRound round = new TontineRound();
        round.setTontineId(tontineId);
        round.setRoundNumber(1);
        round.setStatus(RoundStatus.SUSPENDED);
        round.setStartDate(LocalDateTime.now());
        round.setEndDate(LocalDateTime.now().plusDays(30));
        Long roundId = roundRepository.save(round).getId();

        TontineMember member = new TontineMember();
        member.setTontineId(tontineId);
        member.setUserId(1L);
        member.setSequentialOrder(1);
        member.setStatus(MemberStatus.ACTIVE);
        member.setAccountId(UUID.randomUUID());
        Long memberId = memberRepository.save(member).getId();

        // 3 exécutions séquentielles explicites du même vrai bean Spring.
        suspendedRoundRetryScheduler.retrySuspendedRounds();
        suspendedRoundRetryScheduler.retrySuspendedRounds();
        suspendedRoundRetryScheduler.retrySuspendedRounds();

        List<TontineRound> allRounds = roundRepository.findByTontineId(tontineId);
        assertThat(allRounds).hasSize(1); // aucune duplication
        TontineRound finalState = roundRepository.findById(roundId).orElseThrow();
        assertThat(finalState.getStatus()).isEqualTo(RoundStatus.ASSIGNED);
        assertThat(finalState.getBeneficiaryId()).isEqualTo(memberId);
    }

    @Test
    void orphanedRetry_executedThreeTimesSequentially_createsExactlyOneNextRoundNoDuplication() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontine(creator);
        saveConfig(tontineId, RotationType.SEQUENTIAL, BigDecimal.valueOf(200), ContributionFrequency.WEEKLY);

        TontineRound roundN = new TontineRound();
        roundN.setTontineId(tontineId);
        roundN.setRoundNumber(3);
        roundN.setStatus(RoundStatus.COMPLETED);
        roundN.setStartDate(LocalDateTime.now().minusDays(10));
        roundN.setEndDate(LocalDateTime.now().minusDays(3));
        roundRepository.save(roundN);
        // Aucun round #4 : tontine orpheline.

        orphanedCompletedRoundRetryScheduler.retryOrphanedCompletedRounds();
        orphanedCompletedRoundRetryScheduler.retryOrphanedCompletedRounds();
        orphanedCompletedRoundRetryScheduler.retryOrphanedCompletedRounds();

        List<TontineRound> allRounds = roundRepository.findByTontineId(tontineId);
        assertThat(allRounds).hasSize(2); // round #3 (COMPLETED) + exactement un round #4 (PLANNED)
        assertThat(allRounds).noneMatch(r -> r.getRoundNumber() == 5); // aucun round #5 parasite
        TontineRound next = allRounds.stream()
                .filter(r -> r.getRoundNumber() == 4)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Round #4 introuvable"));
        assertThat(next.getStatus()).isEqualTo(RoundStatus.PLANNED);
        assertThat(next.getBeneficiaryId()).isNull();
    }

    @Test
    void completion_executedThreeTimesSequentially_createsExactlyOneNextRoundNoDuplication() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontine(creator);
        saveConfig(tontineId, RotationType.SEQUENTIAL, BigDecimal.valueOf(150), ContributionFrequency.DAILY);

        TontineRound roundN = new TontineRound();
        roundN.setTontineId(tontineId);
        roundN.setRoundNumber(1);
        roundN.setBeneficiaryId(9L);
        roundN.setStatus(RoundStatus.ASSIGNED);
        roundN.setStartDate(LocalDateTime.now().minusDays(2));
        roundN.setEndDate(LocalDateTime.now().minusMinutes(1)); // déjà expiré
        Long roundNId = roundRepository.save(roundN).getId();

        roundCompletionScheduler.completeExpiredRounds();
        roundCompletionScheduler.completeExpiredRounds();
        roundCompletionScheduler.completeExpiredRounds();

        List<TontineRound> allRounds = roundRepository.findByTontineId(tontineId);
        assertThat(allRounds).hasSize(2); // round #1 (COMPLETED) + exactement un round #2 (PLANNED)
        assertThat(allRounds).noneMatch(r -> r.getRoundNumber() == 3); // aucun round #3 parasite

        TontineRound completed = roundRepository.findById(roundNId).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(RoundStatus.COMPLETED);

        TontineRound next = allRounds.stream()
                .filter(r -> r.getRoundNumber() == 2)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Round #2 introuvable"));
        assertThat(next.getStatus()).isEqualTo(RoundStatus.PLANNED);
    }

    private Long createTontine(UUID creator) {
        Tontine tontine = new Tontine();
        tontine.setName("Idempotence");
        tontine.setCreatorUserId(creator);
        tontine.setCreatedAt(LocalDateTime.now());
        return tontineRepository.save(tontine).getId();
    }

    private void saveConfig(Long tontineId, RotationType rotationType, BigDecimal amount, ContributionFrequency frequency) {
        TontineConfig config = new TontineConfig();
        config.setTontineId(tontineId);
        config.setRotationType(rotationType);
        config.setContributionAmount(amount);
        config.setContributionFrequency(frequency);
        config.setMaxMembers(10);
        configRepository.save(config);
    }
}
