package com.tontiflow.application.service;

import com.tontiflow.domain.enums.MemberStatus;
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
 * Tests unitaires de {@link DisbursementApplicationService} (décision R6) —
 * symétrique à {@code ContributionApplicationServiceTest} (décision R3).
 * Le bénéficiaire reste une donnée serveur ({@code TontineRound.beneficiaryId}),
 * jamais une entrée client ; {@code TontineMemberRepository} n'est consulté
 * que pour vérifier le statut du membre bénéficiaire (décision R18 D5 :
 * PENDING interdit de décaissement).
 */
@ExtendWith(MockitoExtension.class)
class DisbursementApplicationServiceTest {

    @Mock
    private TontineRepository tontineRepository;
    @Mock
    private TontineRoundRepository roundRepository;
    @Mock
    private TontineMemberRepository memberRepository;
    @Mock
    private FinancialServiceClient financialServiceClient;

    private DisbursementApplicationService service;

    private static TontineMember member(Long id, MemberStatus status) {
        TontineMember m = new TontineMember();
        m.setId(id);
        m.setStatus(status);
        return m;
    }

    private static Tontine tontine(Long id, UUID creator) {
        Tontine t = new Tontine();
        t.setId(id);
        t.setCreatorUserId(creator);
        return t;
    }

    private static TontineRound round(Long id, Long tontineId, BigDecimal amount, Long beneficiaryId) {
        TontineRound r = new TontineRound();
        r.setId(id);
        r.setTontineId(tontineId);
        r.setAmount(amount);
        r.setBeneficiaryId(beneficiaryId);
        return r;
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new DisbursementApplicationService(
                tontineRepository, roundRepository, memberRepository, financialServiceClient);
    }

    @Test
    void recordDisbursement_asCreator_withAssignedBeneficiary_delegatesToFinancialService() {
        UUID creator = UUID.randomUUID();
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontine(1L, creator)));
        when(roundRepository.findById(10L)).thenReturn(Optional.of(round(10L, 1L, new BigDecimal("5000.00"), 100L)));
        when(memberRepository.findById(100L)).thenReturn(Optional.of(member(100L, MemberStatus.ACTIVE)));

        TontineRound result = service.recordDisbursement(1L, 10L, creator, "Bearer test-token");

        assertThat(result.getAmount()).isEqualByComparingTo("5000.00");
        verify(financialServiceClient).recordDisbursement(1L, 10L, 100L, new BigDecimal("5000.00"), "Bearer test-token");
    }

    @Test
    void recordDisbursement_asNonCreator_isForbidden_andNoFinancialCallMade() {
        UUID creator = UUID.randomUUID();
        UUID attacker = UUID.randomUUID();
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontine(1L, creator)));

        assertThatThrownBy(() -> service.recordDisbursement(1L, 10L, attacker, "Bearer test-token"))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(financialServiceClient, roundRepository);
    }

    @Test
    void recordDisbursement_withRoundFromAnotherTontine_isRejected_andNoFinancialCallMade() {
        UUID creator = UUID.randomUUID();
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontine(1L, creator)));
        when(roundRepository.findById(10L)).thenReturn(Optional.of(round(10L, 999L, new BigDecimal("5000.00"), 100L)));

        assertThatThrownBy(() -> service.recordDisbursement(1L, 10L, creator, "Bearer test-token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("n'appartient pas");

        verifyNoInteractions(financialServiceClient);
    }

    @Test
    void recordDisbursement_withUnknownTontine_throwsIllegalArgumentException() {
        when(tontineRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recordDisbursement(1L, 10L, UUID.randomUUID(), "Bearer test-token"))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(financialServiceClient);
    }

    @Test
    void recordDisbursement_withUnknownRound_throwsIllegalArgumentException() {
        UUID creator = UUID.randomUUID();
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontine(1L, creator)));
        when(roundRepository.findById(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recordDisbursement(1L, 10L, creator, "Bearer test-token"))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(financialServiceClient);
    }

    // TEST contrainte de donnee (decision R6, cf. Etape 1) : round sans
    // beneficiaire assigne -> refus, aucune ecriture.
    @Test
    void recordDisbursement_withNoBeneficiaryAssigned_isRejected_andNoFinancialCallMade() {
        UUID creator = UUID.randomUUID();
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontine(1L, creator)));
        when(roundRepository.findById(10L)).thenReturn(Optional.of(round(10L, 1L, new BigDecimal("5000.00"), null)));

        assertThatThrownBy(() -> service.recordDisbursement(1L, 10L, creator, "Bearer test-token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bénéficiaire");

        verifyNoInteractions(financialServiceClient);
    }

    // TEST : montant provient du round, jamais du client (aucun parametre
    // "amount" n'existe meme sur la signature de service).
    @Test
    void recordDisbursement_amountAlwaysComesFromRound_neverFromCaller() {
        UUID creator = UUID.randomUUID();
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontine(1L, creator)));
        when(roundRepository.findById(10L)).thenReturn(Optional.of(round(10L, 1L, new BigDecimal("1234.56"), 100L)));
        when(memberRepository.findById(100L)).thenReturn(Optional.of(member(100L, MemberStatus.ACTIVE)));

        service.recordDisbursement(1L, 10L, creator, "Bearer test-token");

        verify(financialServiceClient).recordDisbursement(eq(1L), eq(10L), eq(100L), eq(new BigDecimal("1234.56")), any());
    }

    // Décision R18 D5 : un bénéficiaire PENDING (non lié à un compte TontiFlow)
    // ne peut pas recevoir de décaissement — aucun appel financial-service.
    @Test
    void recordDisbursement_withPendingBeneficiary_isRejected_andNoFinancialCallMade() {
        UUID creator = UUID.randomUUID();
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontine(1L, creator)));
        when(roundRepository.findById(10L)).thenReturn(Optional.of(round(10L, 1L, new BigDecimal("5000.00"), 100L)));
        when(memberRepository.findById(100L)).thenReturn(Optional.of(member(100L, MemberStatus.PENDING)));

        assertThatThrownBy(() -> service.recordDisbursement(1L, 10L, creator, "Bearer test-token"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("membre actif");

        verifyNoInteractions(financialServiceClient);
    }
}
