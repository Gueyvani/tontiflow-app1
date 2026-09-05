package com.tontiflow.application.service;

import com.tontiflow.domain.enums.RoundStatus;
import com.tontiflow.domain.model.TontineRound;
import com.tontiflow.infrastructure.repository.TontineRoundRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Couvre {@link SuspendedRoundRetryScheduler} (décision S1b) : reprise
 * automatique des rounds {@code SUSPENDED}, traitement isolé par round,
 * revalidation du statut après verrouillage, aucun {@code callerUserId}
 * requis (déclenchement système).
 */
@ExtendWith(MockitoExtension.class)
class SuspendedRoundRetrySchedulerTest {

    @Mock
    private TontineRoundRepository roundRepository;
    @Mock
    private TontineRoundApplicationService roundApplicationService;

    private SuspendedRoundRetryScheduler scheduler() {
        SuspendedRoundRetryScheduler instance =
                new SuspendedRoundRetryScheduler(roundRepository, roundApplicationService);
        instance.setSelf(instance); // pas de proxy Spring en test unitaire : auto-reference directe
        return instance;
    }

    @Test
    void retrySuspendedRounds_whenNoneSuspended_doesNothing() {
        when(roundRepository.findByStatus(RoundStatus.SUSPENDED)).thenReturn(List.of());

        scheduler().retrySuspendedRounds();

        verifyNoInteractions(roundApplicationService);
        verify(roundRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void retrySuspendedRounds_whenEligibleMemberFound_delegatesToRetryEligibility() {
        TontineRound round = new TontineRound();
        round.setId(1L);
        round.setStatus(RoundStatus.SUSPENDED);

        when(roundRepository.findByStatus(RoundStatus.SUSPENDED)).thenReturn(List.of(round));
        when(roundRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(round));

        scheduler().retrySuspendedRounds();

        verify(roundApplicationService).retryEligibility(round);
    }

    @Test
    void retrySuspendedRounds_whenStillNoEligibleMember_stillDelegatesAndRemainsSuspended() {
        // La décision (rester SUSPENDED ou passer ASSIGNED) est de la responsabilité
        // de retryEligibility, déjà testée séparément ; ce scheduler se contente de
        // déléguer après verrouillage/revalidation, quel que soit le résultat.
        TontineRound round = new TontineRound();
        round.setId(1L);
        round.setStatus(RoundStatus.SUSPENDED);

        when(roundRepository.findByStatus(RoundStatus.SUSPENDED)).thenReturn(List.of(round));
        when(roundRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(round));
        when(roundApplicationService.retryEligibility(round)).thenReturn(round); // reste SUSPENDED

        scheduler().retrySuspendedRounds();

        verify(roundApplicationService).retryEligibility(round);
        assertThat(round.getStatus()).isEqualTo(RoundStatus.SUSPENDED);
    }

    @Test
    void retryOneSuspendedRound_whenRoundNotFound_throwsIllegalArgumentException() {
        when(roundRepository.findByIdForUpdate(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> scheduler().retryOneSuspendedRound(1L))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(roundApplicationService);
    }

    @Test
    void retryOneSuspendedRound_whenStatusNoLongerSuspended_isNoOp() {
        // Revalidation apres verrou : le round a ete traite entre-temps (ex. un
        // assign-beneficiary manuel concurrent) - ne doit pas etre retraite.
        TontineRound round = new TontineRound();
        round.setId(1L);
        round.setStatus(RoundStatus.ASSIGNED);
        when(roundRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(round));

        scheduler().retryOneSuspendedRound(1L);

        verifyNoInteractions(roundApplicationService);
    }

    @Test
    void retrySuspendedRounds_processesEachRoundInIsolation_andContinuesAfterFailure() {
        TontineRound roundThatFails = new TontineRound();
        roundThatFails.setId(1L);
        TontineRound roundThatSucceeds = new TontineRound();
        roundThatSucceeds.setId(2L);
        roundThatSucceeds.setStatus(RoundStatus.SUSPENDED);

        when(roundRepository.findByStatus(RoundStatus.SUSPENDED))
                .thenReturn(List.of(roundThatFails, roundThatSucceeds));
        when(roundRepository.findByIdForUpdate(1L)).thenThrow(new RuntimeException("panne inattendue"));
        when(roundRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(roundThatSucceeds));

        scheduler().retrySuspendedRounds();

        verify(roundApplicationService).retryEligibility(roundThatSucceeds);
    }
}
