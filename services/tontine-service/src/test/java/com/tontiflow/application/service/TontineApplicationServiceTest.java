package com.tontiflow.application.service;

import com.tontiflow.domain.enums.ContributionFrequency;
import com.tontiflow.domain.enums.MemberStatus;
import com.tontiflow.domain.enums.NonCompliantBehavior;
import com.tontiflow.domain.enums.RotationType;
import com.tontiflow.domain.model.Tontine;
import com.tontiflow.domain.model.TontineConfig;
import com.tontiflow.domain.model.TontineMember;
import com.tontiflow.infrastructure.repository.TontineConfigRepository;
import com.tontiflow.infrastructure.repository.TontineMemberRepository;
import com.tontiflow.infrastructure.repository.TontineRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TontineApplicationServiceTest {

    @Mock
    private TontineRepository tontineRepository;
    @Mock
    private TontineMemberRepository memberRepository;
    @Mock
    private TontineConfigRepository configRepository;
    @Mock
    private TontineRoundApplicationService roundApplicationService;

    private TontineApplicationService service() {
        return new TontineApplicationService(tontineRepository, memberRepository, configRepository,
                roundApplicationService);
    }

    private static Tontine tontineOwnedBy(UUID creator) {
        Tontine tontine = new Tontine();
        tontine.setCreatorUserId(creator);
        return tontine;
    }

    @Test
    void createTontine_setsNameCreatorAndTimestamp() {
        UUID creator = UUID.randomUUID();
        when(tontineRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Tontine result = service().createTontine(
                "Tontine des collègues", creator, BigDecimal.valueOf(100), ContributionFrequency.MONTHLY,
                10, RotationType.SEQUENTIAL, NonCompliantBehavior.POSTPONE, true);

        assertThat(result.getName()).isEqualTo("Tontine des collègues");
        assertThat(result.getCreatorUserId()).isEqualTo(creator);
        assertThat(result.getCreatedAt()).isNotNull();
    }

    @Test
    void createTontine_alsoCreatesConfigWithProvidedValues() {
        UUID creator = UUID.randomUUID();
        Tontine saved = new Tontine();
        saved.setId(1L);
        when(tontineRepository.save(any())).thenReturn(saved);
        when(configRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service().createTontine(
                "Tontine des collègues", creator, BigDecimal.valueOf(100), ContributionFrequency.WEEKLY,
                5, RotationType.RANDOM, NonCompliantBehavior.SUSPEND, false);

        verify(configRepository).save(argThat(config ->
                config.getTontineId().equals(1L)
                        && config.getContributionAmount().equals(BigDecimal.valueOf(100))
                        && config.getContributionFrequency() == ContributionFrequency.WEEKLY
                        && config.getMaxMembers() == 5
                        && config.getRotationType() == RotationType.RANDOM
                        && config.getNonCompliantBehavior() == NonCompliantBehavior.SUSPEND
                        && !config.isReorganisationAllowed()));
    }

    @Test
    void createTontine_alsoCreatesFirstRoundViaRoundApplicationService() {
        UUID creator = UUID.randomUUID();
        Tontine saved = new Tontine();
        saved.setId(1L);
        when(tontineRepository.save(any())).thenReturn(saved);
        when(configRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service().createTontine(
                "Tontine des collègues", creator, BigDecimal.valueOf(100), ContributionFrequency.WEEKLY,
                5, RotationType.RANDOM, NonCompliantBehavior.SUSPEND, false);

        verify(roundApplicationService).createRoundForTontine(eq(1L), argThat(config ->
                config.getContributionAmount().equals(BigDecimal.valueOf(100))
                        && config.getContributionFrequency() == ContributionFrequency.WEEKLY), eq(1));
    }

    @Test
    void createTontine_withNullReorganisationAllowed_defaultsToTrue() {
        UUID creator = UUID.randomUUID();
        Tontine saved = new Tontine();
        saved.setId(1L);
        when(tontineRepository.save(any())).thenReturn(saved);
        when(configRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service().createTontine(
                "Tontine des collègues", creator, BigDecimal.valueOf(100), ContributionFrequency.MONTHLY,
                10, RotationType.SEQUENTIAL, NonCompliantBehavior.POSTPONE, null);

        verify(configRepository).save(argThat(TontineConfig::isReorganisationAllowed));
    }

    @Test
    void addMember_whenTontineNotFound_throwsIllegalArgumentException() {
        when(tontineRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().addMember(1L, 42L, 0, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(memberRepository);
    }

    @Test
    void addMember_whenCallerNotCreator_throwsAccessDeniedException() {
        UUID creator = UUID.randomUUID();
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontineOwnedBy(creator)));

        assertThatThrownBy(() -> service().addMember(1L, 42L, 0, UUID.randomUUID()))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(memberRepository);
    }

    @Test
    void addMember_whenAlreadyMember_throwsIllegalStateException() {
        UUID creator = UUID.randomUUID();
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontineOwnedBy(creator)));
        when(memberRepository.findByTontineIdAndUserId(1L, 42L)).thenReturn(Optional.of(new TontineMember()));

        assertThatThrownBy(() -> service().addMember(1L, 42L, 0, creator))
                .isInstanceOf(IllegalStateException.class);

        verify(memberRepository, never()).save(any());
    }

    @Test
    void addMember_whenNotYetMember_savesAndReturns() {
        UUID creator = UUID.randomUUID();
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontineOwnedBy(creator)));
        when(memberRepository.findByTontineIdAndUserId(1L, 42L)).thenReturn(Optional.empty());
        when(memberRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        TontineMember result = service().addMember(1L, 42L, 3, creator);

        assertThat(result.getTontineId()).isEqualTo(1L);
        assertThat(result.getUserId()).isEqualTo(42L);
        assertThat(result.getSequentialOrder()).isEqualTo(3);
        // Décision R18 D1 : membre créé PENDING, non lié à un compte.
        assertThat(result.getStatus()).isEqualTo(MemberStatus.PENDING);
        assertThat(result.getAccountId()).isNull();
    }

    @Test
    void listTontines_returnsOnlyCallersOwnTontines() {
        UUID creator = UUID.randomUUID();
        Tontine tontine = tontineOwnedBy(creator);
        when(tontineRepository.findByCreatorUserId(creator)).thenReturn(List.of(tontine));

        List<Tontine> result = service().listTontines(creator);

        assertThat(result).containsExactly(tontine);
    }

    @Test
    void listTontines_whenNoneOwned_returnsEmptyList() {
        UUID creator = UUID.randomUUID();
        when(tontineRepository.findByCreatorUserId(creator)).thenReturn(List.of());

        List<Tontine> result = service().listTontines(creator);

        assertThat(result).isEmpty();
    }

    @Test
    void getTontine_whenNotFound_throwsIllegalArgumentException() {
        when(tontineRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().getTontine(99L, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getTontine_whenCallerNotCreator_throwsAccessDeniedException() {
        UUID creator = UUID.randomUUID();
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontineOwnedBy(creator)));

        assertThatThrownBy(() -> service().getTontine(1L, UUID.randomUUID()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void listMembers_whenTontineNotFound_throwsIllegalArgumentException() {
        when(tontineRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().listMembers(99L, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(memberRepository);
    }

    @Test
    void listMembers_whenTontineExists_returnsMembers() {
        UUID creator = UUID.randomUUID();
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontineOwnedBy(creator)));
        TontineMember member = new TontineMember();
        when(memberRepository.findByTontineId(1L)).thenReturn(List.of(member));

        List<TontineMember> result = service().listMembers(1L, creator);

        assertThat(result).containsExactly(member);
    }

    @Test
    void getConfig_whenTontineNotFound_throwsIllegalArgumentException() {
        when(tontineRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().getConfig(99L, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getConfig_whenCallerNotCreator_throwsAccessDeniedException() {
        UUID creator = UUID.randomUUID();
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontineOwnedBy(creator)));

        assertThatThrownBy(() -> service().getConfig(1L, UUID.randomUUID()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void getConfig_whenConfigNotFound_throwsIllegalArgumentException() {
        UUID creator = UUID.randomUUID();
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontineOwnedBy(creator)));
        when(configRepository.findByTontineId(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().getConfig(1L, creator))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getConfig_whenFound_returnsConfig() {
        UUID creator = UUID.randomUUID();
        TontineConfig config = new TontineConfig();
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontineOwnedBy(creator)));
        when(configRepository.findByTontineId(1L)).thenReturn(Optional.of(config));

        TontineConfig result = service().getConfig(1L, creator);

        assertThat(result).isSameAs(config);
    }

    @Test
    void updateConfig_whenTontineNotFound_throwsIllegalArgumentException() {
        when(tontineRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().updateConfig(
                1L, UUID.randomUUID(), BigDecimal.TEN, ContributionFrequency.MONTHLY, 5,
                RotationType.SEQUENTIAL, NonCompliantBehavior.POSTPONE, true))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(configRepository);
    }

    @Test
    void updateConfig_whenCallerNotCreator_throwsAccessDeniedException() {
        UUID creator = UUID.randomUUID();
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontineOwnedBy(creator)));

        assertThatThrownBy(() -> service().updateConfig(
                1L, UUID.randomUUID(), BigDecimal.TEN, ContributionFrequency.MONTHLY, 5,
                RotationType.SEQUENTIAL, NonCompliantBehavior.POSTPONE, true))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(configRepository);
    }

    @Test
    void updateConfig_whenConfigNotFound_throwsIllegalArgumentException() {
        UUID creator = UUID.randomUUID();
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontineOwnedBy(creator)));
        when(configRepository.findByTontineId(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().updateConfig(
                1L, creator, BigDecimal.TEN, ContributionFrequency.MONTHLY, 5,
                RotationType.SEQUENTIAL, NonCompliantBehavior.POSTPONE, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateConfig_whenReducingMaxMembersBelowCurrentMembers_throwsIllegalStateException() {
        UUID creator = UUID.randomUUID();
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontineOwnedBy(creator)));
        when(configRepository.findByTontineId(1L)).thenReturn(Optional.of(new TontineConfig()));
        when(memberRepository.findByTontineId(1L)).thenReturn(List.of(new TontineMember(), new TontineMember(), new TontineMember()));

        assertThatThrownBy(() -> service().updateConfig(
                1L, creator, BigDecimal.TEN, ContributionFrequency.MONTHLY, 2,
                RotationType.SEQUENTIAL, NonCompliantBehavior.POSTPONE, true))
                .isInstanceOf(IllegalStateException.class);

        verify(configRepository, never()).save(any());
    }

    @Test
    void updateConfig_withNullReorganisationAllowed_defaultsToTrue() {
        UUID creator = UUID.randomUUID();
        TontineConfig config = new TontineConfig();
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontineOwnedBy(creator)));
        when(configRepository.findByTontineId(1L)).thenReturn(Optional.of(config));
        when(memberRepository.findByTontineId(1L)).thenReturn(List.of());
        when(configRepository.save(config)).thenReturn(config);

        TontineConfig result = service().updateConfig(
                1L, creator, BigDecimal.TEN, ContributionFrequency.MONTHLY, 5,
                RotationType.SEQUENTIAL, NonCompliantBehavior.POSTPONE, null);

        assertThat(result.isReorganisationAllowed()).isTrue();
    }

    @Test
    void updateConfig_asCreator_updatesAndSavesFields() {
        UUID creator = UUID.randomUUID();
        TontineConfig config = new TontineConfig();
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontineOwnedBy(creator)));
        when(configRepository.findByTontineId(1L)).thenReturn(Optional.of(config));
        when(memberRepository.findByTontineId(1L)).thenReturn(List.of(new TontineMember()));
        when(configRepository.save(config)).thenReturn(config);

        TontineConfig result = service().updateConfig(
                1L, creator, BigDecimal.valueOf(250), ContributionFrequency.DAILY, 8,
                RotationType.MANUAL, NonCompliantBehavior.ALLOW, false);

        assertThat(result.getContributionAmount()).isEqualByComparingTo(BigDecimal.valueOf(250));
        assertThat(result.getContributionFrequency()).isEqualTo(ContributionFrequency.DAILY);
        assertThat(result.getMaxMembers()).isEqualTo(8);
        assertThat(result.getRotationType()).isEqualTo(RotationType.MANUAL);
        assertThat(result.getNonCompliantBehavior()).isEqualTo(NonCompliantBehavior.ALLOW);
        assertThat(result.isReorganisationAllowed()).isFalse();
    }
}
