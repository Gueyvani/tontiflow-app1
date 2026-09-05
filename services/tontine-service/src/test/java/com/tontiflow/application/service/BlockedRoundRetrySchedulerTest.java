package com.tontiflow.application.service;

import com.tontiflow.domain.enums.RoundStatus;
import com.tontiflow.domain.model.TontineConfig;
import com.tontiflow.domain.model.TontineRound;
import com.tontiflow.infrastructure.repository.TontineConfigRepository;
import com.tontiflow.infrastructure.repository.TontineRoundRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Couvre {@link BlockedRoundRetryScheduler} (décision P1) : reprise
 * automatique des rounds {@code BLOCKED}, traitement isolé par round,
 * revalidation du statut après verrouillage, réutilisation directe de
 * {@link RoundCompletionScheduler#completeNow}, aucun {@code callerUserId}
 * requis (déclenchement système). Même structure que {@link
 * SuspendedRoundRetrySchedulerTest}.
 */
@ExtendWith(MockitoExtension.class)
class BlockedRoundRetrySchedulerTest {

    @Mock
    private TontineRoundRepository roundRepository;
    @Mock
    private TontineConfigRepository configRepository;
    @Mock
    private TontineRoundApplicationService roundApplicationService;

    private RoundCompletionScheduler completionScheduler() {
        return new RoundCompletionScheduler(roundRepository, configRepository, roundApplicationService);
    }

    private BlockedRoundRetryScheduler scheduler() {
        BlockedRoundRetryScheduler instance =
                new BlockedRoundRetryScheduler(roundRepository, configRepository, completionScheduler());
        instance.setSelf(instance); // pas de proxy Spring en test unitaire : auto-reference directe
        return instance;
    }

    @Test
    void retryBlockedRounds_whenNoneBlocked_doesNothing() {
        when(roundRepository.findByStatus(RoundStatus.BLOCKED)).thenReturn(List.of());

        scheduler().retryBlockedRounds();

        verifyNoInteractions(configRepository, roundApplicationService);
        verify(roundRepository, never()).findByIdForUpdate(any());
    }

    // TEST 3 (P1) — reprise après correction : BLOCKED + TontineConfig
    // désormais présente → COMPLETED + round suivant créé.
    @Test
    void retryOneBlockedRound_whenConfigNowPresent_completesAndCreatesNextRound() {
        TontineRound round = new TontineRound();
        round.setId(1L);
        round.setTontineId(10L);
        round.setRoundNumber(2);
        round.setStatus(RoundStatus.BLOCKED);
        round.setEndDate(LocalDateTime.now().minusMinutes(1));
        TontineConfig config = new TontineConfig();

        when(roundRepository.findByStatus(RoundStatus.BLOCKED)).thenReturn(List.of(round));
        when(roundRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(round));
        when(roundRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(configRepository.findByTontineId(10L)).thenReturn(Optional.of(config));

        scheduler().retryBlockedRounds();

        assertThat(round.getStatus()).isEqualTo(RoundStatus.COMPLETED);
        verify(roundApplicationService).createRoundForTontine(10L, config, 3);
    }

    // CAS 1 (P1) — toujours bloqué : reste BLOCKED, aucun round suivant,
    // aucune nouvelle écriture (déjà loggé au blocage initial).
    @Test
    void retryOneBlockedRound_whenConfigStillMissing_remainsBlockedWithoutSideEffects() {
        TontineRound round = new TontineRound();
        round.setId(1L);
        round.setTontineId(10L);
        round.setStatus(RoundStatus.BLOCKED);

        when(roundRepository.findByStatus(RoundStatus.BLOCKED)).thenReturn(List.of(round));
        when(roundRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(round));
        when(configRepository.findByTontineId(10L)).thenReturn(Optional.empty());

        scheduler().retryBlockedRounds();

        assertThat(round.getStatus()).isEqualTo(RoundStatus.BLOCKED);
        verify(roundRepository, never()).save(any());
        verifyNoInteractions(roundApplicationService);
    }

    @Test
    void retryOneBlockedRound_whenRoundNotFound_throwsIllegalArgumentException() {
        when(roundRepository.findByIdForUpdate(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> scheduler().retryOneBlockedRound(1L))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(configRepository, roundApplicationService);
    }

    // CAS 3 (P1) — revalidation après verrou : le round a été traité
    // entre-temps (ex. correction concurrente) - ne doit pas être retraité.
    @Test
    void retryOneBlockedRound_whenStatusNoLongerBlocked_isNoOp() {
        TontineRound round = new TontineRound();
        round.setId(1L);
        round.setStatus(RoundStatus.COMPLETED);
        when(roundRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(round));

        scheduler().retryOneBlockedRound(1L);

        verifyNoInteractions(configRepository, roundApplicationService);
        verify(roundRepository, never()).save(any());
    }

    @Test
    void retryBlockedRounds_processesEachRoundInIsolation_andContinuesAfterFailure() {
        TontineRound roundThatFails = new TontineRound();
        roundThatFails.setId(1L);
        TontineRound roundThatSucceeds = new TontineRound();
        roundThatSucceeds.setId(2L);
        roundThatSucceeds.setTontineId(20L);
        roundThatSucceeds.setRoundNumber(1);
        roundThatSucceeds.setStatus(RoundStatus.BLOCKED);
        roundThatSucceeds.setEndDate(LocalDateTime.now().minusMinutes(1));
        TontineConfig config = new TontineConfig();

        when(roundRepository.findByStatus(RoundStatus.BLOCKED))
                .thenReturn(List.of(roundThatFails, roundThatSucceeds));
        when(roundRepository.findByIdForUpdate(1L)).thenThrow(new RuntimeException("panne inattendue"));
        when(roundRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(roundThatSucceeds));
        when(roundRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(configRepository.findByTontineId(20L)).thenReturn(Optional.of(config));

        scheduler().retryBlockedRounds();

        assertThat(roundThatSucceeds.getStatus()).isEqualTo(RoundStatus.COMPLETED);
        verify(roundApplicationService).createRoundForTontine(20L, config, 2);
    }
}
