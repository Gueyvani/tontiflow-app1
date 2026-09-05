package com.tontiflow.application.service;

import com.tontiflow.domain.model.Tontine;
import com.tontiflow.domain.model.TontineMember;
import com.tontiflow.domain.model.TontineRound;
import com.tontiflow.infrastructure.client.FinancialServiceClient;
import com.tontiflow.infrastructure.repository.TontineMemberRepository;
import com.tontiflow.infrastructure.repository.TontineRepository;
import com.tontiflow.infrastructure.repository.TontineRoundRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests unitaires de {@link ContributionApplicationService} (décision R3) :
 * autorisation créateur, revalidation membre/round, dérivation du montant,
 * anti-BOLA/IDOR (§7/§44).
 */
@ExtendWith(MockitoExtension.class)
class ContributionApplicationServiceTest {

    @Mock
    private TontineRepository tontineRepository;
    @Mock
    private TontineRoundRepository roundRepository;
    @Mock
    private TontineMemberRepository memberRepository;
    @Mock
    private FinancialServiceClient financialServiceClient;

    private ContributionApplicationService service;

    private static Tontine tontine(Long id, UUID creator) {
        Tontine t = new Tontine();
        t.setId(id);
        t.setCreatorUserId(creator);
        return t;
    }

    private static TontineRound round(Long id, Long tontineId, BigDecimal amount) {
        TontineRound r = new TontineRound();
        r.setId(id);
        r.setTontineId(tontineId);
        r.setAmount(amount);
        return r;
    }

    private static TontineMember member(Long id, Long tontineId) {
        TontineMember m = new TontineMember();
        m.setId(id);
        m.setTontineId(tontineId);
        return m;
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new ContributionApplicationService(
                tontineRepository, roundRepository, memberRepository, financialServiceClient);
    }

    @Test
    void recordContribution_asCreator_withValidMemberAndRound_delegatesToFinancialService() {
        UUID creator = UUID.randomUUID();
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontine(1L, creator)));
        when(roundRepository.findById(10L)).thenReturn(Optional.of(round(10L, 1L, new BigDecimal("5000.00"))));
        when(memberRepository.findById(100L)).thenReturn(Optional.of(member(100L, 1L)));

        TontineRound result = service.recordContribution(1L, 10L, 100L, creator, "Bearer test-token");

        assertThat(result.getAmount()).isEqualByComparingTo("5000.00");
        verify(financialServiceClient).recordContribution(1L, 10L, 100L, new BigDecimal("5000.00"), "Bearer test-token");
    }

    // TEST 44.1 : non-createur -> refus, aucune ecriture financiere.
    @Test
    void recordContribution_asNonCreator_isForbidden_andNoFinancialCallMade() {
        UUID creator = UUID.randomUUID();
        UUID attacker = UUID.randomUUID();
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontine(1L, creator)));

        assertThatThrownBy(() -> service.recordContribution(1L, 10L, 100L, attacker, "Bearer test-token"))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(financialServiceClient, roundRepository, memberRepository);
    }

    // TEST 44.3 : round appartenant a une autre tontine -> refus.
    @Test
    void recordContribution_withRoundFromAnotherTontine_isRejected_andNoFinancialCallMade() {
        UUID creator = UUID.randomUUID();
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontine(1L, creator)));
        when(roundRepository.findById(10L)).thenReturn(Optional.of(round(10L, 999L, new BigDecimal("5000.00"))));

        assertThatThrownBy(() -> service.recordContribution(1L, 10L, 100L, creator, "Bearer test-token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("n'appartient pas");

        verifyNoInteractions(financialServiceClient);
        verifyNoInteractions(memberRepository);
    }

    // TEST 44.2 : membre appartenant a une autre tontine -> refus.
    @Test
    void recordContribution_withMemberFromAnotherTontine_isRejected_andNoFinancialCallMade() {
        UUID creator = UUID.randomUUID();
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontine(1L, creator)));
        when(roundRepository.findById(10L)).thenReturn(Optional.of(round(10L, 1L, new BigDecimal("5000.00"))));
        when(memberRepository.findById(100L)).thenReturn(Optional.of(member(100L, 999L)));

        assertThatThrownBy(() -> service.recordContribution(1L, 10L, 100L, creator, "Bearer test-token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("n'appartient pas");

        verifyNoInteractions(financialServiceClient);
    }

    @Test
    void recordContribution_withUnknownTontine_throwsIllegalArgumentException() {
        when(tontineRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recordContribution(1L, 10L, 100L, UUID.randomUUID(), "Bearer test-token"))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(financialServiceClient);
    }

    @Test
    void recordContribution_withUnknownRound_throwsIllegalArgumentException() {
        UUID creator = UUID.randomUUID();
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontine(1L, creator)));
        when(roundRepository.findById(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recordContribution(1L, 10L, 100L, creator, "Bearer test-token"))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(financialServiceClient);
    }

    @Test
    void recordContribution_withUnknownMember_throwsIllegalArgumentException() {
        UUID creator = UUID.randomUUID();
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontine(1L, creator)));
        when(roundRepository.findById(10L)).thenReturn(Optional.of(round(10L, 1L, new BigDecimal("5000.00"))));
        when(memberRepository.findById(100L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recordContribution(1L, 10L, 100L, creator, "Bearer test-token"))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(financialServiceClient);
    }

    // TEST : montant provient du round, jamais du client (aucun parametre "amount" n'existe
    // meme sur la signature de service - preuve structurelle, cf. §46).
    @Test
    void recordContribution_amountAlwaysComesFromRound_neverFromCaller() {
        UUID creator = UUID.randomUUID();
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontine(1L, creator)));
        when(roundRepository.findById(10L)).thenReturn(Optional.of(round(10L, 1L, new BigDecimal("1234.56"))));
        when(memberRepository.findById(100L)).thenReturn(Optional.of(member(100L, 1L)));

        service.recordContribution(1L, 10L, 100L, creator, "Bearer test-token");

        verify(financialServiceClient).recordContribution(eq(1L), eq(10L), eq(100L), eq(new BigDecimal("1234.56")), any());
    }
}
