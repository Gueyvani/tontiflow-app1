package com.tontiflow.application.service;

import com.tontiflow.domain.enums.ContributionFrequency;
import com.tontiflow.domain.enums.RoundStatus;
import com.tontiflow.domain.model.TontineConfig;
import com.tontiflow.domain.model.TontineRound;
import com.tontiflow.infrastructure.repository.TontineConfigRepository;
import com.tontiflow.infrastructure.repository.TontineRoundRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Couvre {@link OrphanedCompletedRoundRetryScheduler} (décision S3b, option
 * S3b-1) : détection d'une tontine orpheline (dernier round {@code
 * COMPLETED} sans {@code PLANNED}), retry de création, no-op si un {@code
 * PLANNED} existe déjà (idempotence), isolation des erreurs par tontine.
 */
@ExtendWith(MockitoExtension.class)
class OrphanedCompletedRoundRetrySchedulerTest {

    @Mock
    private TontineRoundRepository roundRepository;
    @Mock
    private TontineConfigRepository configRepository;
    @Mock
    private TontineRoundApplicationService roundApplicationService;

    private OrphanedCompletedRoundRetryScheduler scheduler() {
        OrphanedCompletedRoundRetryScheduler instance =
                new OrphanedCompletedRoundRetryScheduler(roundRepository, configRepository, roundApplicationService);
        instance.setSelf(instance); // pas de proxy Spring en test unitaire : auto-reference directe
        return instance;
    }

    @Test
    void retryOrphanedCompletedRounds_whenNoneCompleted_doesNothing() {
        when(roundRepository.findDistinctTontineIdsByStatus(RoundStatus.COMPLETED)).thenReturn(List.of());

        scheduler().retryOrphanedCompletedRounds();

        verifyNoInteractions(configRepository, roundApplicationService);
    }

    @Test
    void retryOneTontine_whenLastRoundCompletedAndNoPlanned_createsNextRound() {
        TontineRound completed = new TontineRound();
        completed.setId(1L);
        completed.setTontineId(10L);
        completed.setRoundNumber(2);
        completed.setStatus(RoundStatus.COMPLETED);
        TontineConfig config = new TontineConfig();
        config.setContributionAmount(BigDecimal.TEN);
        config.setContributionFrequency(ContributionFrequency.MONTHLY);

        when(roundRepository.findByTontineId(10L)).thenReturn(List.of(completed));
        when(roundRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(completed));
        when(configRepository.findByTontineId(10L)).thenReturn(Optional.of(config));

        scheduler().retryOneTontine(10L);

        verify(roundApplicationService).createRoundForTontine(10L, config, 3);
    }

    @Test
    void retryOneTontine_whenPlannedAlreadyExists_doesNothing() {
        // Idempotence : deja repris entre-temps (par le job precedent ou une
        // autre execution concurrente) - ne doit pas retenter.
        TontineRound completed = new TontineRound();
        completed.setId(1L);
        completed.setTontineId(10L);
        completed.setRoundNumber(2);
        completed.setStatus(RoundStatus.COMPLETED);
        TontineRound planned = new TontineRound();
        planned.setId(2L);
        planned.setTontineId(10L);
        planned.setRoundNumber(3);
        planned.setStatus(RoundStatus.PLANNED);

        when(roundRepository.findByTontineId(10L)).thenReturn(List.of(completed, planned));
        when(roundRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(planned));

        scheduler().retryOneTontine(10L);

        verifyNoInteractions(configRepository, roundApplicationService);
    }

    @Test
    void retryOneTontine_whenLastRoundNotCompleted_doesNothing() {
        TontineRound assigned = new TontineRound();
        assigned.setId(1L);
        assigned.setTontineId(10L);
        assigned.setRoundNumber(1);
        assigned.setStatus(RoundStatus.ASSIGNED);

        when(roundRepository.findByTontineId(10L)).thenReturn(List.of(assigned));
        when(roundRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(assigned));

        scheduler().retryOneTontine(10L);

        verifyNoInteractions(configRepository, roundApplicationService);
    }

    @Test
    void retryOneTontine_whenNoRoundsAtAll_doesNothing() {
        when(roundRepository.findByTontineId(10L)).thenReturn(List.of());

        scheduler().retryOneTontine(10L);

        verifyNoInteractions(configRepository, roundApplicationService);
        verify(roundRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void retryOneTontine_whenLatestRoundDisappearsBeforeLock_throwsIllegalArgumentException() {
        TontineRound completed = new TontineRound();
        completed.setId(1L);
        completed.setTontineId(10L);
        completed.setRoundNumber(1);
        completed.setStatus(RoundStatus.COMPLETED);

        when(roundRepository.findByTontineId(10L)).thenReturn(List.of(completed));
        when(roundRepository.findByIdForUpdate(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> scheduler().retryOneTontine(10L))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(configRepository, roundApplicationService);
    }

    @Test
    void retryOneTontine_whenConfigMissing_throwsIllegalStateException() {
        TontineRound completed = new TontineRound();
        completed.setId(1L);
        completed.setTontineId(10L);
        completed.setRoundNumber(1);
        completed.setStatus(RoundStatus.COMPLETED);

        when(roundRepository.findByTontineId(10L)).thenReturn(List.of(completed));
        when(roundRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(completed));
        when(configRepository.findByTontineId(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> scheduler().retryOneTontine(10L))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(roundApplicationService);
    }

    @Test
    void retryOneTontine_whenCreationStillConflicts_doesNotPropagate() {
        // Garde K1 (createRoundForTontine) declenchee malgre la revalidation -
        // absorbee, pas de propagation d'exception vers l'appelant du job.
        TontineRound completed = new TontineRound();
        completed.setId(1L);
        completed.setTontineId(10L);
        completed.setRoundNumber(1);
        completed.setStatus(RoundStatus.COMPLETED);
        TontineConfig config = new TontineConfig();

        when(roundRepository.findByTontineId(10L)).thenReturn(List.of(completed));
        when(roundRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(completed));
        when(configRepository.findByTontineId(10L)).thenReturn(Optional.of(config));
        when(roundApplicationService.createRoundForTontine(10L, config, 2))
                .thenThrow(new IllegalStateException("Un round PLANNED existe déjà"));

        scheduler().retryOneTontine(10L); // ne doit pas lever d'exception
    }

    @Test
    void retryOrphanedCompletedRounds_processesEachTontineInIsolation_andContinuesAfterFailure() {
        TontineRound completedForFailingTontine = new TontineRound();
        completedForFailingTontine.setId(1L);
        completedForFailingTontine.setTontineId(20L);
        completedForFailingTontine.setRoundNumber(1);
        completedForFailingTontine.setStatus(RoundStatus.COMPLETED);

        TontineRound completedForSucceedingTontine = new TontineRound();
        completedForSucceedingTontine.setId(2L);
        completedForSucceedingTontine.setTontineId(10L);
        completedForSucceedingTontine.setRoundNumber(1);
        completedForSucceedingTontine.setStatus(RoundStatus.COMPLETED);
        TontineConfig config = new TontineConfig();

        when(roundRepository.findDistinctTontineIdsByStatus(RoundStatus.COMPLETED))
                .thenReturn(List.of(20L, 10L));
        when(roundRepository.findByTontineId(20L)).thenThrow(new RuntimeException("panne inattendue"));
        when(roundRepository.findByTontineId(10L)).thenReturn(List.of(completedForSucceedingTontine));
        when(roundRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(completedForSucceedingTontine));
        when(configRepository.findByTontineId(10L)).thenReturn(Optional.of(config));

        scheduler().retryOrphanedCompletedRounds();

        verify(roundApplicationService).createRoundForTontine(10L, config, 2);
    }
}
