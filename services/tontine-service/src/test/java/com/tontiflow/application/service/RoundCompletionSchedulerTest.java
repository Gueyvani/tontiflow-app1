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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Couvre {@link RoundCompletionScheduler} : complétion automatique des
 * rounds {@code ASSIGNED} expirés (décision O2) et création du round
 * suivant (décisions C2/M1), y compris les cas défensifs (état modifié
 * entre-temps, configuration manquante, doublon PLANNED déjà présent).
 */
@ExtendWith(MockitoExtension.class)
class RoundCompletionSchedulerTest {

    @Mock
    private TontineRoundRepository roundRepository;
    @Mock
    private TontineConfigRepository configRepository;
    @Mock
    private TontineRoundApplicationService roundApplicationService;

    private RoundCompletionScheduler scheduler() {
        RoundCompletionScheduler instance =
                new RoundCompletionScheduler(roundRepository, configRepository, roundApplicationService);
        instance.setSelf(instance); // pas de proxy Spring en test unitaire : auto-reference directe
        return instance;
    }

    @Test
    void completeExpiredRounds_whenNoneExpired_doesNothing() {
        when(roundRepository.findByStatusAndEndDateBefore(eq(RoundStatus.ASSIGNED), any())).thenReturn(List.of());

        scheduler().completeExpiredRounds();

        verifyNoInteractions(configRepository, roundApplicationService);
        verify(roundRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void completeExpiredRounds_processesEachExpiredRoundInIsolation_andContinuesAfterFailure() {
        TontineRound roundThatFails = new TontineRound();
        roundThatFails.setId(1L);
        TontineRound roundThatSucceeds = new TontineRound();
        roundThatSucceeds.setId(2L);
        roundThatSucceeds.setTontineId(10L);
        roundThatSucceeds.setRoundNumber(1);
        roundThatSucceeds.setStatus(RoundStatus.ASSIGNED);
        roundThatSucceeds.setEndDate(LocalDateTime.now().minusMinutes(1));

        when(roundRepository.findByStatusAndEndDateBefore(eq(RoundStatus.ASSIGNED), any()))
                .thenReturn(List.of(roundThatFails, roundThatSucceeds));
        when(roundRepository.findByIdForUpdate(1L)).thenThrow(new RuntimeException("panne inattendue"));
        when(roundRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(roundThatSucceeds));
        when(configRepository.findByTontineId(10L)).thenReturn(Optional.of(new TontineConfig()));
        when(roundRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        scheduler().completeExpiredRounds();

        // Le round en échec ne bloque pas le traitement du second round.
        assertThat(roundThatSucceeds.getStatus()).isEqualTo(RoundStatus.COMPLETED);
        verify(roundApplicationService).createRoundForTontine(eq(10L), any(), eq(2));
    }

    @Test
    void completeRoundAndCreateNext_whenRoundNotFound_throwsIllegalArgumentException() {
        when(roundRepository.findByIdForUpdate(1L)).thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> scheduler().completeRoundAndCreateNext(1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void completeRoundAndCreateNext_whenStatusNoLongerAssigned_isNoOp() {
        TontineRound round = new TontineRound();
        round.setId(1L);
        round.setStatus(RoundStatus.COMPLETED); // déjà traité entre-temps (concurrence)
        round.setEndDate(LocalDateTime.now().minusMinutes(1));
        when(roundRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(round));

        scheduler().completeRoundAndCreateNext(1L);

        verify(roundRepository, never()).save(any());
        verifyNoInteractions(configRepository, roundApplicationService);
    }

    @Test
    void completeRoundAndCreateNext_whenEndDateNoLongerPast_isNoOp() {
        TontineRound round = new TontineRound();
        round.setId(1L);
        round.setStatus(RoundStatus.ASSIGNED);
        round.setEndDate(LocalDateTime.now().plusDays(1));
        when(roundRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(round));

        scheduler().completeRoundAndCreateNext(1L);

        verify(roundRepository, never()).save(any());
        verifyNoInteractions(configRepository, roundApplicationService);
    }

    // TEST 1 (P1) — config absente : plus d'exception utilisée pour rollback,
    // le round bascule vers BLOCKED (jamais COMPLETED) et aucun round
    // suivant n'est tenté. Remplace l'ancien test qui vérifiait le
    // comportement à corriger (rollback + retry silencieux).
    @Test
    void completeRoundAndCreateNext_whenConfigMissing_setsBlockedWithoutExceptionOrNextRound() {
        TontineRound round = new TontineRound();
        round.setId(1L);
        round.setTontineId(10L);
        round.setStatus(RoundStatus.ASSIGNED);
        round.setEndDate(LocalDateTime.now().minusMinutes(1));
        when(roundRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(round));
        when(roundRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(configRepository.findByTontineId(10L)).thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatCode(() -> scheduler().completeRoundAndCreateNext(1L))
                .doesNotThrowAnyException();

        assertThat(round.getStatus()).isEqualTo(RoundStatus.BLOCKED);
        verify(roundRepository, times(1)).save(round);
        // TEST 4 (P1) — aucune création partielle : le round suivant n'est
        // jamais tenté quand la configuration est absente.
        verifyNoInteractions(roundApplicationService);
    }

    @Test
    void completeRoundAndCreateNext_whenNextRoundCreationConflicts_completesAnywayWithoutPropagating() {
        TontineRound round = new TontineRound();
        round.setId(1L);
        round.setTontineId(10L);
        round.setRoundNumber(4);
        round.setStatus(RoundStatus.ASSIGNED);
        round.setEndDate(LocalDateTime.now().minusMinutes(1));
        TontineConfig config = new TontineConfig();
        when(roundRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(round));
        when(roundRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(configRepository.findByTontineId(10L)).thenReturn(Optional.of(config));
        when(roundApplicationService.createRoundForTontine(10L, config, 5))
                .thenThrow(new IllegalStateException("Un round PLANNED existe déjà"));

        scheduler().completeRoundAndCreateNext(1L);

        assertThat(round.getStatus()).isEqualTo(RoundStatus.COMPLETED);
    }

    @Test
    void completeRoundAndCreateNext_happyPath_completesAndCreatesNextRound() {
        TontineRound round = new TontineRound();
        round.setId(1L);
        round.setTontineId(10L);
        round.setRoundNumber(2);
        round.setStatus(RoundStatus.ASSIGNED);
        round.setEndDate(LocalDateTime.now().minusMinutes(1));
        TontineConfig config = new TontineConfig();
        config.setContributionAmount(BigDecimal.TEN);
        config.setContributionFrequency(ContributionFrequency.MONTHLY);
        when(roundRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(round));
        when(roundRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(configRepository.findByTontineId(10L)).thenReturn(Optional.of(config));

        scheduler().completeRoundAndCreateNext(1L);

        assertThat(round.getStatus()).isEqualTo(RoundStatus.COMPLETED);
        verify(roundApplicationService).createRoundForTontine(10L, config, 3);
    }
}
