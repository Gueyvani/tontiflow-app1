package com.tontiflow.application.service;

import com.tontiflow.domain.enums.ContributionFrequency;
import com.tontiflow.domain.enums.RotationType;
import com.tontiflow.domain.enums.RoundStatus;
import com.tontiflow.domain.model.Tontine;
import com.tontiflow.domain.model.TontineConfig;
import com.tontiflow.domain.model.TontineMember;
import com.tontiflow.domain.model.TontineRound;
import com.tontiflow.domain.model.RoundRotationHistory;
import com.tontiflow.infrastructure.repository.RoundRotationHistoryRepository;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PHASE E — Étapes 2 à 5 : tests de concurrence RÉELLE (vrais threads, vraie
 * transaction Spring par thread, H2 réel, aucun mock de repository).
 *
 * <p><b>Limite explicitement documentée</b> : H2 (profil {@code test},
 * {@code jdbc:h2:mem:testdb}, sans {@code MODE=PostgreSQL} explicite) est
 * utilisé ici, pas PostgreSQL (le SGBD réel de production). Le verrou
 * pessimiste ({@code SELECT ... FOR UPDATE} via {@code @Lock
 * (PESSIMISTIC_WRITE)}) est supporté par H2 mais son comportement exact de
 * blocage entre transactions concurrentes peut différer de PostgreSQL. Les
 * résultats ci-dessous sont donc classés {@code [PROUVÉ PAR TEST CONCURRENT
 * RÉEL]} pour ce qui a été observé avec H2, et ne remplacent pas un test
 * équivalent sur PostgreSQL réel (ex. via Testcontainers, non disponible
 * dans l'infrastructure de test actuelle du module — proposé, non créé).</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(JwtTestSecurityConfiguration.class)
class RoundConcurrencyIntegrationTest {

    @Autowired
    private TontineRepository tontineRepository;
    @Autowired
    private TontineConfigRepository configRepository;
    @Autowired
    private TontineMemberRepository memberRepository;
    @Autowired
    private TontineRoundRepository roundRepository;
    @Autowired
    private RoundRotationHistoryRepository historyRepository;
    @Autowired
    private TontineRoundApplicationService roundApplicationService;
    @Autowired
    private RoundCompletionScheduler roundCompletionScheduler;
    @Autowired
    private SuspendedRoundRetryScheduler suspendedRoundRetryScheduler;
    @Autowired
    private OrphanedCompletedRoundRetryScheduler orphanedCompletedRoundRetryScheduler;

    // ------------------------------------------------------------------
    // Étape 2 : deux assign-beneficiary concurrents sur le même round
    // ------------------------------------------------------------------
    @Test
    void concurrentAssignBeneficiary_onSameRound_yieldsSingleConsistentBeneficiary() throws Exception {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontine(creator);
        saveConfig(tontineId, RotationType.SEQUENTIAL);
        Long member1Id = saveMember(tontineId, 1L, 1);
        Long member2Id = saveMember(tontineId, 2L, 2);
        Long roundId = saveRound(tontineId, 1, RoundStatus.PLANNED, null).getId();

        Callable<TontineRound> task = () -> roundApplicationService.assignNextRoundBeneficiary(tontineId, roundId, creator);

        List<TontineRound> results = runConcurrently(task, task);

        TontineRound finalRound = roundRepository.findById(roundId).orElseThrow();
        assertThat(finalRound.getStatus()).isEqualTo(RoundStatus.ASSIGNED);
        assertThat(finalRound.getBeneficiaryId()).isNotNull();
        assertThat(finalRound.getBeneficiaryId()).isIn(member1Id, member2Id);

        // Les deux appels doivent avoir vu le meme resultat final (le second
        // etant idempotent) - pas de beneficiaire incoherent entre les deux.
        assertThat(results.get(0).getBeneficiaryId()).isEqualTo(finalRound.getBeneficiaryId());
        assertThat(results.get(1).getBeneficiaryId()).isEqualTo(finalRound.getBeneficiaryId());
    }

    // ------------------------------------------------------------------
    // Étape 3 : deux exécutions concurrentes du RoundCompletionScheduler
    // sur le même round ASSIGNED expiré
    // ------------------------------------------------------------------
    @Test
    void concurrentCompletion_onSameExpiredRound_createsExactlyOneNextRound() throws Exception {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontine(creator);
        saveConfig(tontineId, RotationType.SEQUENTIAL);
        Long memberId = saveMember(tontineId, 1L, 1);

        TontineRound assigned = saveRound(tontineId, 1, RoundStatus.ASSIGNED, memberId);
        assigned.setEndDate(LocalDateTime.now().minusMinutes(1));
        roundRepository.save(assigned);

        Callable<Void> task = () -> {
            roundCompletionScheduler.completeExpiredRounds();
            return null;
        };

        runConcurrently(task, task);

        List<TontineRound> allRounds = roundRepository.findByTontineId(tontineId);
        assertThat(allRounds).hasSize(2); // round #1 COMPLETED + round #2 PLANNED, jamais de doublon

        TontineRound round1 = allRounds.stream().filter(r -> r.getRoundNumber() == 1).findFirst().orElseThrow();
        assertThat(round1.getStatus()).isEqualTo(RoundStatus.COMPLETED);

        List<TontineRound> round2Candidates = allRounds.stream().filter(r -> r.getRoundNumber() == 2).toList();
        assertThat(round2Candidates).hasSize(1); // un seul round #2, jamais deux
        assertThat(round2Candidates.get(0).getStatus()).isEqualTo(RoundStatus.PLANNED);
    }

    // ------------------------------------------------------------------
    // Étape 4 : deux exécutions concurrentes du SuspendedRoundRetryScheduler
    // sur le même round SUSPENDED
    // ------------------------------------------------------------------
    @Test
    void concurrentSuspendedRetry_onSameRound_yieldsSingleConsistentOutcome() throws Exception {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontine(creator);
        saveConfig(tontineId, RotationType.SEQUENTIAL);
        Long memberId = saveMember(tontineId, 1L, 1);
        Long roundId = saveRound(tontineId, 1, RoundStatus.SUSPENDED, null).getId();

        Callable<Void> task = () -> {
            suspendedRoundRetryScheduler.retrySuspendedRounds();
            return null;
        };

        runConcurrently(task, task);

        TontineRound finalRound = roundRepository.findById(roundId).orElseThrow();
        assertThat(finalRound.getStatus()).isEqualTo(RoundStatus.ASSIGNED);
        assertThat(finalRound.getBeneficiaryId()).isEqualTo(memberId);

        List<TontineRound> allRounds = roundRepository.findByTontineId(tontineId);
        assertThat(allRounds).hasSize(1); // aucune ecriture dupliquee, aucun round fantome
    }

    // ------------------------------------------------------------------
    // Étape 5 : deux exécutions concurrentes du
    // OrphanedCompletedRoundRetryScheduler sur la même tontine orpheline
    // ------------------------------------------------------------------
    @Test
    void concurrentOrphanedRetry_onSameTontine_createsExactlyOneNextRound() throws Exception {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontine(creator);
        saveConfig(tontineId, RotationType.SEQUENTIAL);
        saveMember(tontineId, 1L, 1);
        TontineRound completed = saveRound(tontineId, 1, RoundStatus.COMPLETED, 999L);

        Callable<Void> task = () -> {
            orphanedCompletedRoundRetryScheduler.retryOrphanedCompletedRounds();
            return null;
        };

        runConcurrently(task, task);

        List<TontineRound> allRounds = roundRepository.findByTontineId(tontineId);
        List<TontineRound> planned = allRounds.stream().filter(r -> r.getStatus() == RoundStatus.PLANNED).toList();
        assertThat(planned).hasSize(1); // un seul round #2 PLANNED, jamais de doublon
        assertThat(planned.get(0).getRoundNumber()).isEqualTo(2);
    }

    // ------------------------------------------------------------------
    // Décision R11 (corrections techniques) : deux replaceBeneficiary
    // concurrents sur le même round ASSIGNED — comblait un gap identifié à
    // l'audit R11 (aucune preuve de concurrence pour cette mutation, malgré
    // l'usage documenté de findByIdForUpdate). Chaque appel remplace
    // effectivement le bénéficiaire (pas d'idempotence par construction,
    // contrairement à assignNextRoundBeneficiary) : les deux doivent réussir
    // séquentiellement grâce au verrou pessimiste, produire exactement deux
    // lignes d'historique (jamais zéro, jamais plus de deux), et le round
    // final doit porter l'un des deux bénéficiaires proposés, jamais une
    // valeur corrompue ou un état intermédiaire.
    // ------------------------------------------------------------------
    @Test
    void concurrentReplaceBeneficiary_onSameRound_yieldsExactlyTwoHistoryEntriesAndConsistentFinalState() throws Exception {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontine(creator);
        saveConfig(tontineId, RotationType.SEQUENTIAL);
        Long initialBeneficiaryId = saveMember(tontineId, 1L, 1);
        Long candidateA = saveMember(tontineId, 2L, 2);
        Long candidateB = saveMember(tontineId, 3L, 3);
        Long roundId = saveRound(tontineId, 1, RoundStatus.ASSIGNED, initialBeneficiaryId).getId();

        Callable<TontineRound> replaceWithA = () ->
                roundApplicationService.replaceBeneficiary(roundId, candidateA, "remplacement A", "testeur-A", creator);
        Callable<TontineRound> replaceWithB = () ->
                roundApplicationService.replaceBeneficiary(roundId, candidateB, "remplacement B", "testeur-B", creator);

        List<TontineRound> results = runConcurrently(replaceWithA, replaceWithB);

        // Aucune exception inattendue (deja garanti par runConcurrently/future.get) :
        // les deux appels ont reellement modifie le round, chacun a son tour.
        assertThat(results.get(0).getBeneficiaryId()).isIn(candidateA, candidateB);
        assertThat(results.get(1).getBeneficiaryId()).isIn(candidateA, candidateB);

        TontineRound finalRound = roundRepository.findById(roundId).orElseThrow();
        assertThat(finalRound.getStatus()).isEqualTo(RoundStatus.ASSIGNED);
        assertThat(finalRound.getBeneficiaryId()).isIn(candidateA, candidateB);

        List<RoundRotationHistory> history = historyRepository.findByRoundId(roundId);
        assertThat(history).hasSize(2); // jamais zero, jamais plus de deux - aucune ecriture perdue ni dupliquee
        assertThat(history).extracting(RoundRotationHistory::getNewBeneficiaryId)
                .containsExactlyInAnyOrder(candidateA, candidateB);
    }

    /**
     * Lance deux tâches réellement en parallèle (threads distincts,
     * transactions Spring distinctes, pas de mock), synchronisées pour
     * démarrer ensemble via {@link CountDownLatch}, et attend les deux
     * résultats. Aucune exception inattendue ne doit être levée par l'un ou
     * l'autre thread (sinon {@code get()} la propage et le test échoue).
     */
    @SafeVarargs
    private <T> List<T> runConcurrently(Callable<T>... tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.length);
        CountDownLatch ready = new CountDownLatch(tasks.length);
        CountDownLatch go = new CountDownLatch(1);

        List<Future<T>> futures = new java.util.ArrayList<>();
        for (Callable<T> task : tasks) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                go.await();
                return task.call();
            }));
        }

        ready.await(5, TimeUnit.SECONDS);
        go.countDown();

        List<T> results = new java.util.ArrayList<>();
        for (Future<T> future : futures) {
            results.add(future.get(15, TimeUnit.SECONDS));
        }
        executor.shutdown();
        return results;
    }

    private Long createTontine(UUID creator) {
        Tontine tontine = new Tontine();
        tontine.setName("Concurrence");
        tontine.setCreatorUserId(creator);
        tontine.setCreatedAt(LocalDateTime.now());
        return tontineRepository.save(tontine).getId();
    }

    private void saveConfig(Long tontineId, RotationType rotationType) {
        TontineConfig config = new TontineConfig();
        config.setTontineId(tontineId);
        config.setRotationType(rotationType);
        config.setContributionAmount(BigDecimal.valueOf(100));
        config.setContributionFrequency(ContributionFrequency.MONTHLY);
        config.setMaxMembers(10);
        configRepository.save(config);
    }

    private Long saveMember(Long tontineId, Long userId, int sequentialOrder) {
        TontineMember member = new TontineMember();
        member.setTontineId(tontineId);
        member.setUserId(userId);
        member.setSequentialOrder(sequentialOrder);
        return memberRepository.save(member).getId();
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
