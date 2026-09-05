package com.tontiflow.application.service;

import com.tontiflow.domain.enums.Currency;
import com.tontiflow.domain.enums.FinancialAccountType;
import com.tontiflow.domain.model.FinancialAccount;
import com.tontiflow.domain.model.JournalEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests unitaires de {@link ContributionService} (décision R3) : vérifie
 * que le cas d'usage traduit correctement une contribution en un appel
 * {@link LedgerService} équilibré, sans jamais accéder aux repositories
 * financiers directement.
 */
@ExtendWith(MockitoExtension.class)
class ContributionServiceTest {

    @Mock
    private LedgerService ledgerService;

    private ContributionService contributionService;

    private FinancialAccount accountWithId(UUID id) {
        FinancialAccount account = new FinancialAccount();
        account.setId(id);
        return account;
    }

    @Test
    void recordContribution_createsBalancedEntry_debitTontine_creditMember() {
        contributionService = new ContributionService(ledgerService);
        UUID tontineAccountId = UUID.randomUUID();
        UUID memberAccountId = UUID.randomUUID();
        when(ledgerService.getOrCreateAccount(10L, FinancialAccountType.TONTINE, Currency.MRU))
                .thenReturn(accountWithId(tontineAccountId));
        when(ledgerService.getOrCreateAccount(123L, FinancialAccountType.MEMBER, Currency.MRU))
                .thenReturn(accountWithId(memberAccountId));
        JournalEntry expected = new JournalEntry();
        when(ledgerService.record(any(), any(), any(), any(), any(), any())).thenReturn(expected);

        JournalEntry result = contributionService.recordContribution(
                10L, 25L, 123L, new BigDecimal("5000.00"), Currency.MRU);

        assertThat(result).isSameAs(expected);

        ArgumentCaptor<List<PostingLine>> linesCaptor = ArgumentCaptor.forClass(List.class);
        verify(ledgerService).record(
                eq("contribution:10:25:123"), eq("CONTRIBUTION_RECORDED"), eq("contribution:10:25:123"),
                any(), eq(Currency.MRU), linesCaptor.capture());

        List<PostingLine> lines = linesCaptor.getValue();
        assertThat(lines).hasSize(2);
        // Convention R3 : Debit = compte TONTINE (destination), Credit = compte MEMBER (source).
        PostingLine tontineLine = lines.stream().filter(l -> l.financialAccountId().equals(tontineAccountId)).findFirst().orElseThrow();
        PostingLine memberLine = lines.stream().filter(l -> l.financialAccountId().equals(memberAccountId)).findFirst().orElseThrow();
        assertThat(tontineLine.debit()).isEqualByComparingTo("5000.00");
        assertThat(tontineLine.credit()).isEqualByComparingTo("0");
        assertThat(memberLine.debit()).isEqualByComparingTo("0");
        assertThat(memberLine.credit()).isEqualByComparingTo("5000.00");
    }

    @Test
    void buildIdempotencyKey_isDeterministic_fromTontineRoundMember() {
        assertThat(ContributionService.buildIdempotencyKey(10L, 25L, 123L))
                .isEqualTo("contribution:10:25:123");
        assertThat(ContributionService.buildIdempotencyKey(10L, 25L, 123L))
                .isEqualTo(ContributionService.buildIdempotencyKey(10L, 25L, 123L));
    }
}
