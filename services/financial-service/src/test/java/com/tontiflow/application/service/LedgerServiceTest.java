package com.tontiflow.application.service;

import com.tontiflow.domain.enums.Currency;
import com.tontiflow.domain.enums.FinancialAccountStatus;
import com.tontiflow.domain.enums.FinancialAccountType;
import com.tontiflow.domain.model.FinancialAccount;
import com.tontiflow.domain.model.JournalEntry;
import com.tontiflow.domain.model.LedgerLine;
import com.tontiflow.infrastructure.repository.FinancialAccountRepository;
import com.tontiflow.infrastructure.repository.JournalEntryRepository;
import com.tontiflow.infrastructure.repository.LedgerLineRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests unitaires de {@link LedgerService} — invariant comptable absolu
 * (Σdebit = Σcredit), idempotence, création de compte (décision R2).
 */
@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Mock
    private FinancialAccountRepository accountRepository;
    @Mock
    private JournalEntryRepository journalEntryRepository;
    @Mock
    private LedgerLineRepository ledgerLineRepository;

    private LedgerService ledgerService;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        ledgerService = new LedgerService(accountRepository, journalEntryRepository, ledgerLineRepository, fixedClock);
        ledgerService.setSelf(ledgerService); // pas de proxy Spring en test unitaire : auto-reference directe
    }

    // ------------------------------------------------------------------
    // FinancialAccount
    // ------------------------------------------------------------------

    @Test
    void getOrCreateAccount_whenNoneExists_createsActiveAccount() {
        when(accountRepository.findByOwnerReferenceAndAccountType(10L, FinancialAccountType.TONTINE))
                .thenReturn(Optional.empty());
        when(accountRepository.saveAndFlush(any(FinancialAccount.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        FinancialAccount account = ledgerService.getOrCreateAccount(10L, FinancialAccountType.TONTINE, Currency.MRU);

        assertThat(account.getOwnerReference()).isEqualTo(10L);
        assertThat(account.getAccountType()).isEqualTo(FinancialAccountType.TONTINE);
        assertThat(account.getCurrency()).isEqualTo(Currency.MRU);
        assertThat(account.getStatus()).isEqualTo(FinancialAccountStatus.ACTIVE);
        assertThat(account.getCreatedAt()).isEqualTo(FIXED_NOW);
    }

    @Test
    void getOrCreateAccount_whenAlreadyExists_returnsExistingWithoutCreating() {
        FinancialAccount existing = new FinancialAccount();
        when(accountRepository.findByOwnerReferenceAndAccountType(10L, FinancialAccountType.TONTINE))
                .thenReturn(Optional.of(existing));

        FinancialAccount result = ledgerService.getOrCreateAccount(10L, FinancialAccountType.TONTINE, Currency.MRU);

        assertThat(result).isSameAs(existing);
        verify(accountRepository, never()).saveAndFlush(any());
    }

    // TEST idempotence concurrente (creation) : deux appels concurrents sur
    // le meme (ownerReference, accountType) - la contrainte UNIQUE gagne la
    // course pour l'un des deux, l'autre retrouve le compte cree entre-temps.
    @Test
    void getOrCreateAccount_whenConcurrentCreationRaces_recoversExistingAccountInsteadOfFailing() {
        FinancialAccount concurrentlyCreated = new FinancialAccount();
        when(accountRepository.findByOwnerReferenceAndAccountType(10L, FinancialAccountType.TONTINE))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(concurrentlyCreated));
        when(accountRepository.saveAndFlush(any(FinancialAccount.class)))
                .thenThrow(new DataIntegrityViolationException("unique violation"));

        FinancialAccount result = ledgerService.getOrCreateAccount(10L, FinancialAccountType.TONTINE, Currency.MRU);

        assertThat(result).isSameAs(concurrentlyCreated);
    }

    @Test
    void getOrCreateAccount_withNullCurrency_throwsNullPointerException() {
        assertThatThrownBy(() -> ledgerService.getOrCreateAccount(10L, FinancialAccountType.TONTINE, null))
                .isInstanceOf(NullPointerException.class);
    }

    // ------------------------------------------------------------------
    // Ledger — invariant Σdebit = Σcredit
    // ------------------------------------------------------------------

    @Test
    void record_withBalancedEntry_isAccepted() {
        UUID accountA = UUID.randomUUID();
        UUID accountB = UUID.randomUUID();
        FinancialAccount a = new FinancialAccount();
        FinancialAccount b = new FinancialAccount();
        when(accountRepository.findById(accountA)).thenReturn(Optional.of(a));
        when(accountRepository.findById(accountB)).thenReturn(Optional.of(b));
        when(journalEntryRepository.saveAndFlush(any(JournalEntry.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        JournalEntry entry = ledgerService.record(
                "contribution:1:1:1", "CONTRIBUTION", "idem-key-1", "test", Currency.MRU,
                List.of(
                        new PostingLine(accountA, new BigDecimal("100.00"), BigDecimal.ZERO),
                        new PostingLine(accountB, BigDecimal.ZERO, new BigDecimal("100.00"))
                ));

        assertThat(entry.getIdempotencyKey()).isEqualTo("idem-key-1");
        verify(ledgerLineRepository, times(2)).save(any());
    }

    @Test
    void record_withUnbalancedEntry_throwsIllegalArgumentException_andNeverPersists() {
        UUID accountA = UUID.randomUUID();
        UUID accountB = UUID.randomUUID();

        assertThatThrownBy(() -> ledgerService.record(
                "contribution:1:1:1", "CONTRIBUTION", "idem-key-2", "test", Currency.MRU,
                List.of(
                        new PostingLine(accountA, new BigDecimal("100.00"), BigDecimal.ZERO),
                        new PostingLine(accountB, BigDecimal.ZERO, new BigDecimal("90.00"))
                )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("déséquilibrée");

        verifyNoInteractions(journalEntryRepository, ledgerLineRepository);
    }

    @Test
    void record_withLineHavingBothDebitAndCredit_isRejected() {
        UUID accountA = UUID.randomUUID();
        UUID accountB = UUID.randomUUID();

        assertThatThrownBy(() -> ledgerService.record(
                "ref", "CONTRIBUTION", "idem-key-3", "test", Currency.MRU,
                List.of(
                        new PostingLine(accountA, new BigDecimal("50.00"), new BigDecimal("50.00")),
                        new PostingLine(accountB, BigDecimal.ZERO, new BigDecimal("50.00"))
                )))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(journalEntryRepository);
    }

    @Test
    void record_withNegativeAmount_isRejected() {
        UUID accountA = UUID.randomUUID();
        UUID accountB = UUID.randomUUID();

        assertThatThrownBy(() -> ledgerService.record(
                "ref", "CONTRIBUTION", "idem-key-4", "test", Currency.MRU,
                List.of(
                        new PostingLine(accountA, new BigDecimal("-10.00"), BigDecimal.ZERO),
                        new PostingLine(accountB, BigDecimal.ZERO, new BigDecimal("-10.00"))
                )))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void record_withFewerThanTwoLines_isRejected() {
        UUID accountA = UUID.randomUUID();

        assertThatThrownBy(() -> ledgerService.record(
                "ref", "CONTRIBUTION", "idem-key-5", "test", Currency.MRU,
                List.of(new PostingLine(accountA, new BigDecimal("100.00"), BigDecimal.ZERO))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void record_withUnknownAccount_throwsIllegalArgumentException() {
        UUID unknownAccount = UUID.randomUUID();
        UUID accountB = UUID.randomUUID();
        when(accountRepository.findById(unknownAccount)).thenReturn(Optional.empty());
        when(journalEntryRepository.saveAndFlush(any(JournalEntry.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        assertThatThrownBy(() -> ledgerService.record(
                "ref", "CONTRIBUTION", "idem-key-6", "test", Currency.MRU,
                List.of(
                        new PostingLine(unknownAccount, new BigDecimal("100.00"), BigDecimal.ZERO),
                        new PostingLine(accountB, BigDecimal.ZERO, new BigDecimal("100.00"))
                )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("introuvable");
    }

    // ------------------------------------------------------------------
    // Idempotence
    // ------------------------------------------------------------------

    // TEST : meme idempotencyKey = une seule operation (deuxieme appel
    // detecte la violation UNIQUE et retourne l'ecriture existante).
    @Test
    void record_whenIdempotencyKeyAlreadyUsed_returnsExistingEntry_doesNotDuplicate() {
        UUID accountA = UUID.randomUUID();
        UUID accountB = UUID.randomUUID();
        JournalEntry existing = new JournalEntry();
        existing.setIdempotencyKey("dup-key");
        when(journalEntryRepository.saveAndFlush(any(JournalEntry.class)))
                .thenThrow(new DataIntegrityViolationException("unique violation"));
        when(journalEntryRepository.findByIdempotencyKey("dup-key")).thenReturn(Optional.of(existing));

        JournalEntry result = ledgerService.record(
                "ref", "CONTRIBUTION", "dup-key", "test", Currency.MRU,
                List.of(
                        new PostingLine(accountA, new BigDecimal("100.00"), BigDecimal.ZERO),
                        new PostingLine(accountB, BigDecimal.ZERO, new BigDecimal("100.00"))
                ));

        assertThat(result).isSameAs(existing);
        verifyNoInteractions(ledgerLineRepository);
    }

    // ------------------------------------------------------------------
    // Balance
    // ------------------------------------------------------------------

    @Test
    void computeBalance_delegatesToRepository_debitMinusCredit() {
        UUID accountId = UUID.randomUUID();
        when(ledgerLineRepository.computeBalance(accountId)).thenReturn(new BigDecimal("42.00"));

        BigDecimal balance = ledgerService.computeBalance(accountId);

        assertThat(balance).isEqualByComparingTo("42.00");
    }

    // ------------------------------------------------------------------
    // getAccountBalance (décision R7)
    // ------------------------------------------------------------------

    @Test
    void getAccountBalance_whenAccountExists_returnsComputedBalance_noDuplicatedFormula() {
        UUID accountId = UUID.randomUUID();
        FinancialAccount account = new FinancialAccount();
        account.setId(accountId);
        account.setOwnerReference(10L);
        account.setAccountType(FinancialAccountType.TONTINE);
        account.setCurrency(Currency.MRU);
        when(accountRepository.findByOwnerReferenceAndAccountType(10L, FinancialAccountType.TONTINE))
                .thenReturn(Optional.of(account));
        when(ledgerLineRepository.computeBalance(accountId)).thenReturn(new BigDecimal("5000.00"));

        AccountBalance result = ledgerService.getAccountBalance(10L, FinancialAccountType.TONTINE);

        assertThat(result.ownerReference()).isEqualTo(10L);
        assertThat(result.accountType()).isEqualTo(FinancialAccountType.TONTINE);
        assertThat(result.currency()).isEqualTo(Currency.MRU);
        assertThat(result.balance()).isEqualByComparingTo("5000.00");
        // La formule Sigma-debit - Sigma-credit reste centralisee dans le repository,
        // jamais recalculee ici : verifie que le seul chemin emprunte est computeBalance.
        verify(ledgerLineRepository).computeBalance(accountId);
    }

    @Test
    void getAccountBalance_whenAccountAbsent_returnsZero_createsNothing() {
        when(accountRepository.findByOwnerReferenceAndAccountType(999L, FinancialAccountType.MEMBER))
                .thenReturn(Optional.empty());

        AccountBalance result = ledgerService.getAccountBalance(999L, FinancialAccountType.MEMBER);

        assertThat(result.ownerReference()).isEqualTo(999L);
        assertThat(result.accountType()).isEqualTo(FinancialAccountType.MEMBER);
        assertThat(result.currency()).isEqualTo(Currency.MRU);
        assertThat(result.balance()).isEqualByComparingTo("0.00");
        // Une lecture ne doit jamais creer de compte ni d'ecriture.
        verifyNoInteractions(ledgerLineRepository);
        verify(accountRepository, never()).save(any());
        verify(accountRepository, never()).saveAndFlush(any());
    }

    @Test
    void getAccountBalance_whenLedgerIsNegative_returnsNegativeValue_noArtificialClamp() {
        UUID accountId = UUID.randomUUID();
        FinancialAccount account = new FinancialAccount();
        account.setId(accountId);
        account.setOwnerReference(20L);
        account.setAccountType(FinancialAccountType.TONTINE);
        account.setCurrency(Currency.MRU);
        when(accountRepository.findByOwnerReferenceAndAccountType(20L, FinancialAccountType.TONTINE))
                .thenReturn(Optional.of(account));
        when(ledgerLineRepository.computeBalance(accountId)).thenReturn(new BigDecimal("-150.00"));

        AccountBalance result = ledgerService.getAccountBalance(20L, FinancialAccountType.TONTINE);

        assertThat(result.balance()).isEqualByComparingTo("-150.00");
    }

    // ------------------------------------------------------------------
    // getAccountStatement (décision R10)
    // ------------------------------------------------------------------

    @Test
    void getAccountStatement_whenAccountExists_returnsLinesMappedFromJournalEntry_noAccountCreated() {
        UUID accountId = UUID.randomUUID();
        FinancialAccount account = new FinancialAccount();
        account.setId(accountId);
        account.setOwnerReference(30L);
        account.setAccountType(FinancialAccountType.TONTINE);
        account.setCurrency(Currency.MRU);
        when(accountRepository.findByOwnerReferenceAndAccountType(30L, FinancialAccountType.TONTINE))
                .thenReturn(Optional.of(account));

        JournalEntry entry = new JournalEntry();
        entry.setEventType("CONTRIBUTION_RECORDED");
        entry.setDescription("Contribution round 90 tontine 30");
        entry.setCreatedAt(FIXED_NOW);

        LedgerLine line = new LedgerLine();
        line.setJournalEntry(entry);
        line.setDebit(new BigDecimal("1000.00"));
        line.setCredit(BigDecimal.ZERO);
        line.setCurrency(Currency.MRU);

        when(ledgerLineRepository.findByFinancialAccount_Id(accountId)).thenReturn(List.of(line));

        List<LedgerLineDetail> result = ledgerService.getAccountStatement(30L, FinancialAccountType.TONTINE);

        assertThat(result).hasSize(1);
        LedgerLineDetail detail = result.get(0);
        assertThat(detail.eventType()).isEqualTo("CONTRIBUTION_RECORDED");
        assertThat(detail.description()).isEqualTo("Contribution round 90 tontine 30");
        assertThat(detail.debit()).isEqualByComparingTo("1000.00");
        assertThat(detail.credit()).isEqualByComparingTo("0");
        assertThat(detail.currency()).isEqualTo(Currency.MRU);
        assertThat(detail.createdAt()).isEqualTo(FIXED_NOW);
        verify(accountRepository, never()).save(any());
        verify(accountRepository, never()).saveAndFlush(any());
    }

    @Test
    void getAccountStatement_whenAccountAbsent_returnsEmptyList_createsNothing() {
        when(accountRepository.findByOwnerReferenceAndAccountType(999L, FinancialAccountType.MEMBER))
                .thenReturn(Optional.empty());

        List<LedgerLineDetail> result = ledgerService.getAccountStatement(999L, FinancialAccountType.MEMBER);

        assertThat(result).isEmpty();
        verifyNoInteractions(ledgerLineRepository);
        verify(accountRepository, never()).save(any());
    }
}
