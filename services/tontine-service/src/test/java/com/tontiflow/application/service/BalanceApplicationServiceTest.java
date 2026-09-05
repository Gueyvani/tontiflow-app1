package com.tontiflow.application.service;

import com.tontiflow.domain.model.Tontine;
import com.tontiflow.domain.model.TontineMember;
import com.tontiflow.infrastructure.client.AccountBalanceResponse;
import com.tontiflow.infrastructure.client.FinancialServiceClient;
import com.tontiflow.infrastructure.client.LedgerLineResponse;
import com.tontiflow.infrastructure.repository.TontineMemberRepository;
import com.tontiflow.infrastructure.repository.TontineRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires de {@link BalanceApplicationService} (décision R7) —
 * symétrique à {@code ContributionApplicationServiceTest}/{@code
 * DisbursementApplicationServiceTest} (décisions R3/R6).
 */
@ExtendWith(MockitoExtension.class)
class BalanceApplicationServiceTest {

    @Mock
    private TontineRepository tontineRepository;
    @Mock
    private TontineMemberRepository memberRepository;
    @Mock
    private FinancialServiceClient financialServiceClient;

    private BalanceApplicationService service;

    private static Tontine tontine(Long id, UUID creator) {
        Tontine t = new Tontine();
        t.setId(id);
        t.setCreatorUserId(creator);
        return t;
    }

    private static TontineMember member(Long id, Long tontineId) {
        TontineMember m = new TontineMember();
        m.setId(id);
        m.setTontineId(tontineId);
        return m;
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new BalanceApplicationService(tontineRepository, memberRepository, financialServiceClient);
    }

    @Test
    void getTontineBalance_asCreator_delegatesToFinancialService() {
        UUID creator = UUID.randomUUID();
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontine(1L, creator)));
        AccountBalanceResponse expected = new AccountBalanceResponse("MRU", new BigDecimal("700.00"));
        when(financialServiceClient.getBalance(1L, "Bearer test-token")).thenReturn(expected);

        AccountBalanceResponse result = service.getTontineBalance(1L, creator, "Bearer test-token");

        assertThat(result).isSameAs(expected);
    }

    @Test
    void getTontineBalance_asNonCreator_isForbidden_andNoFinancialCallMade() {
        UUID creator = UUID.randomUUID();
        UUID attacker = UUID.randomUUID();
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontine(1L, creator)));

        assertThatThrownBy(() -> service.getTontineBalance(1L, attacker, "Bearer test-token"))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(financialServiceClient);
    }

    @Test
    void getTontineBalance_withUnknownTontine_throwsIllegalArgumentException_andNoFinancialCallMade() {
        when(tontineRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getTontineBalance(1L, UUID.randomUUID(), "Bearer test-token"))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(financialServiceClient);
    }

    @Test
    void getMemberBalance_asCreator_withMemberBelongingToTontine_delegatesToFinancialService() {
        UUID creator = UUID.randomUUID();
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontine(1L, creator)));
        when(memberRepository.findById(100L)).thenReturn(Optional.of(member(100L, 1L)));
        AccountBalanceResponse expected = new AccountBalanceResponse("MRU", new BigDecimal("300.00"));
        when(financialServiceClient.getMemberBalance(100L, "Bearer test-token")).thenReturn(expected);

        AccountBalanceResponse result = service.getMemberBalance(1L, 100L, creator, "Bearer test-token");

        assertThat(result).isSameAs(expected);
    }

    @Test
    void getMemberBalance_asNonCreator_isForbidden_andNoFinancialCallMade() {
        UUID creator = UUID.randomUUID();
        UUID attacker = UUID.randomUUID();
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontine(1L, creator)));

        assertThatThrownBy(() -> service.getMemberBalance(1L, 100L, attacker, "Bearer test-token"))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(financialServiceClient, memberRepository);
    }

    @Test
    void getMemberBalance_withUnknownMember_throwsIllegalArgumentException_andNoFinancialCallMade() {
        UUID creator = UUID.randomUUID();
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontine(1L, creator)));
        when(memberRepository.findById(100L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getMemberBalance(1L, 100L, creator, "Bearer test-token"))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(financialServiceClient);
    }

    @Test
    void getMemberBalance_withMemberFromAnotherTontine_isRejected_andNoFinancialCallMade() {
        UUID creator = UUID.randomUUID();
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontine(1L, creator)));
        when(memberRepository.findById(100L)).thenReturn(Optional.of(member(100L, 999L)));

        assertThatThrownBy(() -> service.getMemberBalance(1L, 100L, creator, "Bearer test-token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("n'appartient pas");

        verifyNoInteractions(financialServiceClient);
    }

    @Test
    void getTontineStatement_asCreator_delegatesToFinancialService() {
        UUID creator = UUID.randomUUID();
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontine(1L, creator)));
        List<LedgerLineResponse> expected = List.of(
                new LedgerLineResponse("CONTRIBUTION_RECORDED", "desc", new BigDecimal("1000.00"),
                        BigDecimal.ZERO, "MRU", Instant.now()));
        when(financialServiceClient.getStatement(1L, "Bearer test-token")).thenReturn(expected);

        List<LedgerLineResponse> result = service.getTontineStatement(1L, creator, "Bearer test-token");

        assertThat(result).isSameAs(expected);
    }

    @Test
    void getTontineStatement_asNonCreator_isForbidden_andNoFinancialCallMade() {
        UUID creator = UUID.randomUUID();
        UUID attacker = UUID.randomUUID();
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontine(1L, creator)));

        assertThatThrownBy(() -> service.getTontineStatement(1L, attacker, "Bearer test-token"))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(financialServiceClient);
    }

    @Test
    void getTontineStatement_withUnknownTontine_throwsIllegalArgumentException_andNoFinancialCallMade() {
        when(tontineRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getTontineStatement(1L, UUID.randomUUID(), "Bearer test-token"))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(financialServiceClient);
    }

    @Test
    void getMemberStatement_asCreator_withMemberBelongingToTontine_delegatesToFinancialService() {
        UUID creator = UUID.randomUUID();
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontine(1L, creator)));
        when(memberRepository.findById(100L)).thenReturn(Optional.of(member(100L, 1L)));
        List<LedgerLineResponse> expected = List.of(
                new LedgerLineResponse("DISBURSEMENT_RECORDED", "desc", BigDecimal.ZERO,
                        new BigDecimal("300.00"), "MRU", Instant.now()));
        when(financialServiceClient.getMemberStatement(100L, "Bearer test-token")).thenReturn(expected);

        List<LedgerLineResponse> result = service.getMemberStatement(1L, 100L, creator, "Bearer test-token");

        assertThat(result).isSameAs(expected);
    }

    @Test
    void getMemberStatement_withMemberFromAnotherTontine_isRejected_andNoFinancialCallMade() {
        UUID creator = UUID.randomUUID();
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontine(1L, creator)));
        when(memberRepository.findById(100L)).thenReturn(Optional.of(member(100L, 999L)));

        assertThatThrownBy(() -> service.getMemberStatement(1L, 100L, creator, "Bearer test-token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("n'appartient pas");

        verifyNoInteractions(financialServiceClient);
    }
}
