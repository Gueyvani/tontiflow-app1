package com.tontiflow.application.service;

import com.tontiflow.domain.enums.ContributionFrequency;
import com.tontiflow.domain.enums.NonCompliantBehavior;
import com.tontiflow.domain.enums.RotationType;
import com.tontiflow.domain.enums.RoundStatus;
import com.tontiflow.domain.model.Tontine;
import com.tontiflow.domain.model.TontineConfig;
import com.tontiflow.domain.model.TontineMember;
import com.tontiflow.domain.model.TontineRound;
import com.tontiflow.domain.service.EligibilityEngine;
import com.tontiflow.domain.strategy.RotationStrategy;
import com.tontiflow.domain.strategy.RotationStrategyRegistry;
import com.tontiflow.infrastructure.repository.RoundRotationHistoryRepository;
import com.tontiflow.infrastructure.repository.TontineConfigRepository;
import com.tontiflow.infrastructure.repository.TontineMemberRepository;
import com.tontiflow.infrastructure.repository.TontineRepository;
import com.tontiflow.infrastructure.repository.TontineRoundRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Couvre {@link TontineRoundApplicationService}, y compris le contrôle
 * d'accès au niveau ressource (seul le créateur de la tontine propriétaire
 * du round peut attribuer/remplacer un bénéficiaire — même mécanisme que
 * {@link TontineApplicationService}).
 */
@ExtendWith(MockitoExtension.class)
class TontineRoundApplicationServiceTest {

    @Mock
    private TontineRoundRepository roundRepository;
    @Mock
    private TontineMemberRepository memberRepository;
    @Mock
    private TontineConfigRepository configRepository;
    @Mock
    private RoundRotationHistoryRepository historyRepository;
    @Mock
    private RotationStrategyRegistry strategyRegistry;
    @Mock
    private EligibilityEngine eligibilityEngine;
    @Mock
    private RotationStrategy rotationStrategy;
    @Mock
    private TontineRepository tontineRepository;

    private TontineRoundApplicationService service() {
        return new TontineRoundApplicationService(
                roundRepository, memberRepository, configRepository, historyRepository,
                strategyRegistry, eligibilityEngine, tontineRepository);
    }

    private static Tontine tontineOwnedBy(UUID creator) {
        Tontine tontine = new Tontine();
        tontine.setCreatorUserId(creator);
        return tontine;
    }

    @Test
    void assignNextRoundBeneficiary_whenRoundNotFound_throwsIllegalArgumentException() {
        when(roundRepository.findByIdForUpdate(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().assignNextRoundBeneficiary(1L, 99L, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(configRepository, memberRepository, strategyRegistry, eligibilityEngine, tontineRepository);
    }

    @Test
    void assignNextRoundBeneficiary_whenTontineNotFound_throwsIllegalArgumentException() {
        TontineRound round = new TontineRound();
        round.setId(5L);
        round.setTontineId(1L);
        when(roundRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(round));
        when(tontineRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().assignNextRoundBeneficiary(1L, 5L, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(configRepository, memberRepository, strategyRegistry, eligibilityEngine);
    }

    @Test
    void assignNextRoundBeneficiary_whenCallerNotCreator_throwsAccessDeniedException() {
        UUID creator = UUID.randomUUID();
        TontineRound round = new TontineRound();
        round.setId(5L);
        round.setTontineId(1L);
        when(roundRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(round));
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontineOwnedBy(creator)));

        assertThatThrownBy(() -> service().assignNextRoundBeneficiary(1L, 5L, UUID.randomUUID()))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(configRepository, memberRepository, strategyRegistry, eligibilityEngine);
    }

    @Test
    void assignNextRoundBeneficiary_whenTontineIdInPathDoesNotMatchRoundsTontine_throwsIllegalArgumentException_andWritesNothing() {
        // Correctif R1 : le round appartient reellement a la tontine 1L, mais
        // l'appelant fournit tontineId=2L dans le chemin - doit etre rejete
        // avant toute lecture de configuration/membres d'une autre tontine et
        // avant toute ecriture, quel que soit le proprietaire reel de 2L.
        TontineRound round = new TontineRound();
        round.setId(5L);
        round.setTontineId(1L);
        round.setStatus(RoundStatus.PLANNED);
        when(roundRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(round));

        assertThatThrownBy(() -> service().assignNextRoundBeneficiary(2L, 5L, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(tontineRepository, configRepository, memberRepository, strategyRegistry, eligibilityEngine);
        verify(roundRepository, never()).save(any());
    }

    @Test
    void assignNextRoundBeneficiary_whenAlreadyAssigned_isIdempotentAndSkipsSelection() {
        UUID creator = UUID.randomUUID();
        TontineRound round = new TontineRound();
        round.setId(5L);
        round.setTontineId(1L);
        round.setStatus(RoundStatus.ASSIGNED);
        when(roundRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(round));
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontineOwnedBy(creator)));

        TontineRound result = service().assignNextRoundBeneficiary(1L, 5L, creator);

        assertThat(result).isSameAs(round);
        verifyNoInteractions(configRepository, memberRepository, strategyRegistry, eligibilityEngine);
        verify(roundRepository, never()).save(any());
    }

    @Test
    void assignNextRoundBeneficiary_whenAlreadyCompleted_isIdempotent() {
        UUID creator = UUID.randomUUID();
        TontineRound round = new TontineRound();
        round.setTontineId(1L);
        round.setStatus(RoundStatus.COMPLETED);
        when(roundRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(round));
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontineOwnedBy(creator)));

        TontineRound result = service().assignNextRoundBeneficiary(1L, 5L, creator);

        assertThat(result.getStatus()).isEqualTo(RoundStatus.COMPLETED);
        verify(roundRepository, never()).save(any());
    }

    @Test
    void assignNextRoundBeneficiary_whenConfigMissing_throwsIllegalStateException() {
        UUID creator = UUID.randomUUID();
        TontineRound round = new TontineRound();
        round.setTontineId(1L);
        round.setStatus(RoundStatus.PLANNED);
        when(roundRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(round));
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontineOwnedBy(creator)));
        when(configRepository.findByTontineId(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().assignNextRoundBeneficiary(1L, 5L, creator))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void assignNextRoundBeneficiary_whenNoEligibleMember_suspendsRound() {
        UUID creator = UUID.randomUUID();
        TontineRound round = new TontineRound();
        round.setId(5L);
        round.setTontineId(1L);
        round.setStatus(RoundStatus.PLANNED);
        TontineConfig config = new TontineConfig();
        config.setNonCompliantBehavior(NonCompliantBehavior.POSTPONE);
        TontineMember member = new TontineMember();

        when(roundRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(round));
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontineOwnedBy(creator)));
        when(configRepository.findByTontineId(1L)).thenReturn(Optional.of(config));
        when(memberRepository.findByTontineId(1L)).thenReturn(List.of(member));
        when(roundRepository.findByTontineId(1L)).thenReturn(List.of(round));
        when(eligibilityEngine.isEligible(eq(member), eq(config), anyList())).thenReturn(false);
        when(roundRepository.save(round)).thenReturn(round);

        TontineRound result = service().assignNextRoundBeneficiary(1L, 5L, creator);

        assertThat(result.getStatus()).isEqualTo(RoundStatus.SUSPENDED);
        verify(roundRepository).save(round);
        verifyNoInteractions(strategyRegistry);
    }

    @Test
    void assignNextRoundBeneficiary_whenStrategySelectsNobody_throwsIllegalStateException() {
        UUID creator = UUID.randomUUID();
        TontineRound round = new TontineRound();
        round.setTontineId(1L);
        round.setStatus(RoundStatus.PLANNED);
        TontineConfig config = new TontineConfig();
        config.setRotationType(RotationType.SEQUENTIAL);
        TontineMember member = new TontineMember();
        member.setId(7L);

        when(roundRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(round));
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontineOwnedBy(creator)));
        when(configRepository.findByTontineId(1L)).thenReturn(Optional.of(config));
        when(memberRepository.findByTontineId(1L)).thenReturn(List.of(member));
        when(roundRepository.findByTontineId(1L)).thenReturn(List.of(round));
        when(eligibilityEngine.isEligible(eq(member), eq(config), anyList())).thenReturn(true);
        when(strategyRegistry.getStrategy(RotationType.SEQUENTIAL)).thenReturn(rotationStrategy);
        when(rotationStrategy.selectNextBeneficiary(anyList(), anyList())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().assignNextRoundBeneficiary(1L, 5L, creator))
                .isInstanceOf(IllegalStateException.class);

        verify(roundRepository, never()).save(any());
    }

    @Test
    void assignNextRoundBeneficiary_whenEligibleMemberSelected_assignsAndSaves() {
        UUID creator = UUID.randomUUID();
        TontineRound round = new TontineRound();
        round.setId(5L);
        round.setTontineId(1L);
        round.setStatus(RoundStatus.PLANNED);
        TontineConfig config = new TontineConfig();
        config.setRotationType(RotationType.SEQUENTIAL);
        TontineMember member = new TontineMember();
        member.setId(7L);

        when(roundRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(round));
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontineOwnedBy(creator)));
        when(configRepository.findByTontineId(1L)).thenReturn(Optional.of(config));
        when(memberRepository.findByTontineId(1L)).thenReturn(List.of(member));
        when(roundRepository.findByTontineId(1L)).thenReturn(List.of(round));
        when(eligibilityEngine.isEligible(eq(member), eq(config), anyList())).thenReturn(true);
        when(strategyRegistry.getStrategy(RotationType.SEQUENTIAL)).thenReturn(rotationStrategy);
        when(rotationStrategy.selectNextBeneficiary(anyList(), anyList())).thenReturn(Optional.of(member));
        when(roundRepository.save(round)).thenReturn(round);

        TontineRound result = service().assignNextRoundBeneficiary(1L, 5L, creator);

        assertThat(result.getStatus()).isEqualTo(RoundStatus.ASSIGNED);
        assertThat(result.getBeneficiaryId()).isEqualTo(7L);
        verify(roundRepository).save(round);
    }

    @Test
    void retryEligibility_whenEligibleMemberFound_transitionsToAssigned() {
        TontineRound round = new TontineRound();
        round.setId(5L);
        round.setTontineId(1L);
        round.setStatus(RoundStatus.SUSPENDED);
        TontineConfig config = new TontineConfig();
        config.setRotationType(RotationType.SEQUENTIAL);
        TontineMember member = new TontineMember();
        member.setId(7L);

        when(configRepository.findByTontineId(1L)).thenReturn(Optional.of(config));
        when(memberRepository.findByTontineId(1L)).thenReturn(List.of(member));
        when(roundRepository.findByTontineId(1L)).thenReturn(List.of(round));
        when(eligibilityEngine.isEligible(eq(member), eq(config), anyList())).thenReturn(true);
        when(strategyRegistry.getStrategy(RotationType.SEQUENTIAL)).thenReturn(rotationStrategy);
        when(rotationStrategy.selectNextBeneficiary(anyList(), anyList())).thenReturn(Optional.of(member));
        when(roundRepository.save(round)).thenReturn(round);

        TontineRound result = service().retryEligibility(round);

        assertThat(result.getStatus()).isEqualTo(RoundStatus.ASSIGNED);
        assertThat(result.getBeneficiaryId()).isEqualTo(7L);
    }

    @Test
    void retryEligibility_whenNoEligibleMember_remainsSuspended() {
        TontineRound round = new TontineRound();
        round.setId(5L);
        round.setTontineId(1L);
        round.setStatus(RoundStatus.SUSPENDED);
        TontineConfig config = new TontineConfig();
        config.setNonCompliantBehavior(NonCompliantBehavior.POSTPONE);
        TontineMember member = new TontineMember();

        when(configRepository.findByTontineId(1L)).thenReturn(Optional.of(config));
        when(memberRepository.findByTontineId(1L)).thenReturn(List.of(member));
        when(roundRepository.findByTontineId(1L)).thenReturn(List.of(round));
        when(eligibilityEngine.isEligible(eq(member), eq(config), anyList())).thenReturn(false);
        when(roundRepository.save(round)).thenReturn(round);

        TontineRound result = service().retryEligibility(round);

        assertThat(result.getStatus()).isEqualTo(RoundStatus.SUSPENDED);
        verifyNoInteractions(strategyRegistry);
    }

    @Test
    void retryEligibility_whenConfigMissing_throwsIllegalStateException() {
        TontineRound round = new TontineRound();
        round.setId(5L);
        round.setTontineId(1L);
        round.setStatus(RoundStatus.SUSPENDED);
        when(configRepository.findByTontineId(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().retryEligibility(round))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void replaceBeneficiary_whenRoundNotFound_throwsIllegalArgumentException() {
        when(roundRepository.findByIdForUpdate(5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().replaceBeneficiary(5L, 9L, "raison", "alice", UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(historyRepository, tontineRepository);
    }

    @Test
    void replaceBeneficiary_whenCallerNotCreator_throwsAccessDeniedException() {
        UUID creator = UUID.randomUUID();
        TontineRound round = new TontineRound();
        round.setId(5L);
        round.setTontineId(1L);
        round.setBeneficiaryId(3L);
        when(roundRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(round));
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontineOwnedBy(creator)));

        assertThatThrownBy(() -> service().replaceBeneficiary(5L, 9L, "raison", "alice", UUID.randomUUID()))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(historyRepository);
    }

    @Test
    void replaceBeneficiary_savesAuditHistoryAndUpdatesRound() {
        UUID creator = UUID.randomUUID();
        TontineRound round = new TontineRound();
        round.setId(5L);
        round.setTontineId(1L);
        round.setBeneficiaryId(3L);
        round.setStatus(RoundStatus.ASSIGNED);
        TontineMember newBeneficiary = new TontineMember();
        newBeneficiary.setId(9L);
        newBeneficiary.setTontineId(1L);
        when(roundRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(round));
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontineOwnedBy(creator)));
        when(memberRepository.findByTontineId(1L)).thenReturn(List.of(newBeneficiary));
        when(roundRepository.save(round)).thenReturn(round);

        TontineRound result = service().replaceBeneficiary(5L, 9L, "membre exclu", "alice", creator);

        assertThat(result.getBeneficiaryId()).isEqualTo(9L);
        assertThat(result.getStatus()).isEqualTo(RoundStatus.ASSIGNED);

        verify(historyRepository).save(argThat(history ->
                history.getRoundId().equals(5L)
                        && history.getPreviousBeneficiaryId().equals(3L)
                        && history.getNewBeneficiaryId().equals(9L)
                        && history.getReason().equals("membre exclu")
                        && history.getUpdatedBy().equals("alice")
                        && history.getModificationType().equals("REPLACEMENT")));
    }

    @Test
    void replaceBeneficiary_whenRoundIsCompleted_rejects() {
        // Regle A (Phase F3) : un round COMPLETED est terminal, meme invariant
        // que l'idempotence deja appliquee par assignNextRoundBeneficiary.
        UUID creator = UUID.randomUUID();
        TontineRound round = new TontineRound();
        round.setId(5L);
        round.setTontineId(1L);
        round.setBeneficiaryId(3L);
        round.setStatus(RoundStatus.COMPLETED);
        when(roundRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(round));
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontineOwnedBy(creator)));

        assertThatThrownBy(() -> service().replaceBeneficiary(5L, 9L, "membre exclu", "alice", creator))
                .isInstanceOf(IllegalStateException.class);

        verify(roundRepository, never()).save(any());
        verifyNoInteractions(historyRepository);
    }

    @Test
    void replaceBeneficiary_whenBeneficiaryBelongsToAnotherTontine_rejects() {
        // Regle B (Phase F3) : round.getTontineId() reste l'unique source de
        // verite pour determiner l'appartenance du beneficiaire.
        UUID creator = UUID.randomUUID();
        TontineRound round = new TontineRound();
        round.setId(5L);
        round.setTontineId(1L);
        round.setBeneficiaryId(3L);
        round.setStatus(RoundStatus.ASSIGNED);
        when(roundRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(round));
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontineOwnedBy(creator)));
        when(memberRepository.findByTontineId(1L)).thenReturn(List.of()); // 9L n'appartient pas a la tontine 1L

        assertThatThrownBy(() -> service().replaceBeneficiary(5L, 9L, "membre exclu", "alice", creator))
                .isInstanceOf(IllegalArgumentException.class);

        verify(roundRepository, never()).save(any());
        verifyNoInteractions(historyRepository);
    }

    @Test
    void replaceBeneficiary_whenBeneficiaryBelongsToSameTontine_succeeds() {
        UUID creator = UUID.randomUUID();
        TontineRound round = new TontineRound();
        round.setId(5L);
        round.setTontineId(1L);
        round.setBeneficiaryId(3L);
        round.setStatus(RoundStatus.ASSIGNED);
        TontineMember newBeneficiary = new TontineMember();
        newBeneficiary.setId(9L);
        newBeneficiary.setTontineId(1L);
        when(roundRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(round));
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontineOwnedBy(creator)));
        when(memberRepository.findByTontineId(1L)).thenReturn(List.of(newBeneficiary));
        when(roundRepository.save(round)).thenReturn(round);

        TontineRound result = service().replaceBeneficiary(5L, 9L, "membre exclu", "alice", creator);

        assertThat(result.getBeneficiaryId()).isEqualTo(9L);
        assertThat(result.getStatus()).isEqualTo(RoundStatus.ASSIGNED);
        verify(historyRepository).save(any());
        verify(roundRepository).save(round);
    }

    @Test
    void createRoundForTontine_whenNoPlannedRoundExists_createsPlannedRoundWithComputedFields() {
        TontineConfig config = new TontineConfig();
        config.setContributionAmount(BigDecimal.valueOf(100));
        config.setContributionFrequency(ContributionFrequency.MONTHLY);

        when(roundRepository.findByTontineId(1L)).thenReturn(List.of());
        when(roundRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        LocalDateTime before = LocalDateTime.now();
        TontineRound result = service().createRoundForTontine(1L, config, 3);
        LocalDateTime after = LocalDateTime.now();

        assertThat(result.getTontineId()).isEqualTo(1L);
        assertThat(result.getRoundNumber()).isEqualTo(3);
        assertThat(result.getAmount()).isEqualByComparingTo(BigDecimal.valueOf(100));
        assertThat(result.getStatus()).isEqualTo(RoundStatus.PLANNED);
        assertThat(result.getBeneficiaryId()).isNull();
        assertThat(result.getStartDate()).isBetween(before, after);
        assertThat(result.getEndDate()).isEqualTo(result.getStartDate().plusMonths(1));
    }

    @Test
    void createRoundForTontine_computesEndDate_forDailyFrequency() {
        TontineConfig config = new TontineConfig();
        config.setContributionAmount(BigDecimal.ONE);
        config.setContributionFrequency(ContributionFrequency.DAILY);
        when(roundRepository.findByTontineId(1L)).thenReturn(List.of());
        when(roundRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        TontineRound result = service().createRoundForTontine(1L, config, 1);

        assertThat(result.getEndDate()).isEqualTo(result.getStartDate().plusDays(1));
    }

    @Test
    void createRoundForTontine_computesEndDate_forWeeklyFrequency() {
        TontineConfig config = new TontineConfig();
        config.setContributionAmount(BigDecimal.ONE);
        config.setContributionFrequency(ContributionFrequency.WEEKLY);
        when(roundRepository.findByTontineId(1L)).thenReturn(List.of());
        when(roundRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        TontineRound result = service().createRoundForTontine(1L, config, 1);

        assertThat(result.getEndDate()).isEqualTo(result.getStartDate().plusWeeks(1));
    }

    @Test
    void createRoundForTontine_computesEndDate_forMonthlyFrequency() {
        TontineConfig config = new TontineConfig();
        config.setContributionAmount(BigDecimal.ONE);
        config.setContributionFrequency(ContributionFrequency.MONTHLY);
        when(roundRepository.findByTontineId(1L)).thenReturn(List.of());
        when(roundRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        TontineRound result = service().createRoundForTontine(1L, config, 1);

        assertThat(result.getEndDate()).isEqualTo(result.getStartDate().plusMonths(1));
    }

    @Test
    void createRoundForTontine_whenPlannedRoundAlreadyExists_throwsIllegalStateException() {
        TontineRound existingPlanned = new TontineRound();
        existingPlanned.setStatus(RoundStatus.PLANNED);
        when(roundRepository.findByTontineId(1L)).thenReturn(List.of(existingPlanned));

        assertThatThrownBy(() -> service().createRoundForTontine(1L, new TontineConfig(), 2))
                .isInstanceOf(IllegalStateException.class);

        verify(roundRepository, never()).save(any());
    }

    @Test
    void listRounds_whenCallerIsCreator_returnsAllRounds() {
        UUID creator = UUID.randomUUID();
        TontineRound round1 = new TontineRound();
        TontineRound round2 = new TontineRound();
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontineOwnedBy(creator)));
        when(roundRepository.findByTontineId(1L)).thenReturn(List.of(round1, round2));

        List<TontineRound> result = service().listRounds(1L, creator);

        assertThat(result).containsExactly(round1, round2);
    }

    @Test
    void listRounds_whenTontineNotFound_throwsIllegalArgumentException() {
        when(tontineRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().listRounds(1L, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(roundRepository);
    }

    @Test
    void listRounds_whenCallerNotCreator_throwsAccessDeniedException() {
        UUID creator = UUID.randomUUID();
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontineOwnedBy(creator)));

        assertThatThrownBy(() -> service().listRounds(1L, UUID.randomUUID()))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(roundRepository);
    }

    @Test
    void getRound_whenFoundAndOwnedByTontine_returnsRound() {
        UUID creator = UUID.randomUUID();
        TontineRound round = new TontineRound();
        round.setId(5L);
        round.setTontineId(1L);
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontineOwnedBy(creator)));
        when(roundRepository.findById(5L)).thenReturn(Optional.of(round));

        TontineRound result = service().getRound(1L, 5L, creator);

        assertThat(result).isSameAs(round);
    }

    @Test
    void getRound_whenRoundBelongsToDifferentTontine_throwsIllegalArgumentException() {
        UUID creator = UUID.randomUUID();
        TontineRound round = new TontineRound();
        round.setId(5L);
        round.setTontineId(2L); // n'appartient pas a la tontine 1L demandee
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontineOwnedBy(creator)));
        when(roundRepository.findById(5L)).thenReturn(Optional.of(round));

        assertThatThrownBy(() -> service().getRound(1L, 5L, creator))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getRound_whenRoundNotFound_throwsIllegalArgumentException() {
        UUID creator = UUID.randomUUID();
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontineOwnedBy(creator)));
        when(roundRepository.findById(5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().getRound(1L, 5L, creator))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getRound_whenCallerNotCreator_throwsAccessDeniedException() {
        UUID creator = UUID.randomUUID();
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontineOwnedBy(creator)));

        assertThatThrownBy(() -> service().getRound(1L, 5L, UUID.randomUUID()))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(roundRepository);
    }

    @Test
    void listRotationHistory_whenRoundOwnedByTontine_returnsHistoryOrderedAsPersisted() {
        UUID creator = UUID.randomUUID();
        TontineRound round = new TontineRound();
        round.setId(5L);
        round.setTontineId(1L);
        com.tontiflow.domain.model.RoundRotationHistory entry = new com.tontiflow.domain.model.RoundRotationHistory();
        entry.setRoundId(5L);
        entry.setPreviousBeneficiaryId(10L);
        entry.setNewBeneficiaryId(20L);
        entry.setReason("Membre indisponible");
        entry.setUpdatedBy("creator-username");
        entry.setModificationType("REPLACEMENT");
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontineOwnedBy(creator)));
        when(roundRepository.findById(5L)).thenReturn(Optional.of(round));
        when(historyRepository.findByRoundId(5L)).thenReturn(List.of(entry));

        List<com.tontiflow.domain.model.RoundRotationHistory> result =
                service().listRotationHistory(1L, 5L, creator);

        assertThat(result).containsExactly(entry);
    }

    @Test
    void listRotationHistory_whenRoundBelongsToDifferentTontine_throwsIllegalArgumentException_andNeverQueriesHistory() {
        UUID creator = UUID.randomUUID();
        TontineRound round = new TontineRound();
        round.setId(5L);
        round.setTontineId(2L); // n'appartient pas a la tontine 1L demandee
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontineOwnedBy(creator)));
        when(roundRepository.findById(5L)).thenReturn(Optional.of(round));

        assertThatThrownBy(() -> service().listRotationHistory(1L, 5L, creator))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(historyRepository);
    }

    @Test
    void listRotationHistory_whenCallerNotCreator_throwsAccessDeniedException_andNeverQueriesHistory() {
        UUID creator = UUID.randomUUID();
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontineOwnedBy(creator)));

        assertThatThrownBy(() -> service().listRotationHistory(1L, 5L, UUID.randomUUID()))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(historyRepository, roundRepository);
    }

    @Test
    void getCurrentRound_whenPlannedRoundExists_returnsIt() {
        UUID creator = UUID.randomUUID();
        TontineRound completed = new TontineRound();
        completed.setRoundNumber(1);
        completed.setStatus(RoundStatus.COMPLETED);
        TontineRound planned = new TontineRound();
        planned.setRoundNumber(2);
        planned.setStatus(RoundStatus.PLANNED);
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontineOwnedBy(creator)));
        when(roundRepository.findByTontineId(1L)).thenReturn(List.of(completed, planned));

        TontineRound result = service().getCurrentRound(1L, creator);

        assertThat(result).isSameAs(planned);
    }

    @Test
    void getCurrentRound_whenAssignedRoundExists_returnsIt() {
        UUID creator = UUID.randomUUID();
        TontineRound assigned = new TontineRound();
        assigned.setRoundNumber(3);
        assigned.setStatus(RoundStatus.ASSIGNED);
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontineOwnedBy(creator)));
        when(roundRepository.findByTontineId(1L)).thenReturn(List.of(assigned));

        TontineRound result = service().getCurrentRound(1L, creator);

        assertThat(result).isSameAs(assigned);
    }

    @Test
    void getCurrentRound_whenOnlyTerminalRoundsExist_throwsIllegalArgumentException() {
        UUID creator = UUID.randomUUID();
        TontineRound completed = new TontineRound();
        completed.setStatus(RoundStatus.COMPLETED);
        TontineRound suspended = new TontineRound();
        suspended.setStatus(RoundStatus.SUSPENDED);
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontineOwnedBy(creator)));
        when(roundRepository.findByTontineId(1L)).thenReturn(List.of(completed, suspended));

        assertThatThrownBy(() -> service().getCurrentRound(1L, creator))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getCurrentRound_whenCallerNotCreator_throwsAccessDeniedException() {
        UUID creator = UUID.randomUUID();
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontineOwnedBy(creator)));

        assertThatThrownBy(() -> service().getCurrentRound(1L, UUID.randomUUID()))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(roundRepository);
    }

    @Test
    void createRoundForTontine_whenOnlyNonPlannedRoundsExist_createsNewRound() {
        TontineRound assignedRound = new TontineRound();
        assignedRound.setStatus(RoundStatus.ASSIGNED);
        TontineConfig config = new TontineConfig();
        config.setContributionAmount(BigDecimal.TEN);
        config.setContributionFrequency(ContributionFrequency.DAILY);
        when(roundRepository.findByTontineId(1L)).thenReturn(List.of(assignedRound));
        when(roundRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        TontineRound result = service().createRoundForTontine(1L, config, 2);

        assertThat(result.getStatus()).isEqualTo(RoundStatus.PLANNED);
        assertThat(result.getRoundNumber()).isEqualTo(2);
    }
}
