package com.tontiflow.application.service;

import com.tontiflow.domain.enums.Currency;
import com.tontiflow.domain.model.JournalEntry;
import com.tontiflow.infrastructure.repository.JournalEntryRepository;
import com.tontiflow.infrastructure.repository.LedgerLineRepository;
import com.tontiflow.infrastructure.security.JwtTestSecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test de concurrence RÉELLE du flux de versement complet (décision R6,
 * §15) — symétrique à {@code ContributionConcurrencyIntegrationTest}
 * (décision R3). Identifiants métier ({@code tontineId=31}) distincts de
 * ceux utilisés par les autres classes de concurrence du même contexte
 * Spring/H2 partagé (décision R3/§26 anti-contamination) : {@code
 * LedgerConcurrencyIntegrationTest} (9001/9002), {@code
 * ContributionConcurrencyIntegrationTest} (30/40/500).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(JwtTestSecurityConfiguration.class)
class DisbursementConcurrencyIntegrationTest {

    @Autowired
    private DisbursementService disbursementService;
    @Autowired
    private JournalEntryRepository journalEntryRepository;
    @Autowired
    private LedgerLineRepository ledgerLineRepository;

    @Test
    void recordDisbursement_submittedConcurrentlyBySameIdentifiers_createsExactlyOneEntry() throws Exception {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Callable<JournalEntry>> tasks = IntStream.range(0, threadCount)
                .<Callable<JournalEntry>>mapToObj(i -> () -> {
                    readyLatch.countDown();
                    startLatch.await(5, TimeUnit.SECONDS);
                    return disbursementService.recordDisbursement(
                            31L, 41L, 501L, new BigDecimal("1500.00"), Currency.MRU);
                })
                .toList();

        List<Future<JournalEntry>> futures = executor.invokeAll(tasks);
        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        for (Future<JournalEntry> future : futures) {
            assertThat(future.get(10, TimeUnit.SECONDS)).isNotNull(); // aucune exception non controlee
        }
        executor.shutdown();

        String idempotencyKey = "disbursement:31:41:501";
        long matchingEntries = journalEntryRepository.findAll().stream()
                .filter(e -> idempotencyKey.equals(e.getIdempotencyKey()))
                .count();
        assertThat(matchingEntries).isEqualTo(1);

        JournalEntry entry = journalEntryRepository.findByIdempotencyKey(idempotencyKey).orElseThrow();
        List<com.tontiflow.domain.model.LedgerLine> lines = ledgerLineRepository.findAll().stream()
                .filter(l -> l.getJournalEntry().getId().equals(entry.getId()))
                .toList();
        assertThat(lines).hasSize(2);
        BigDecimal totalDebit = lines.stream().map(l -> l.getDebit()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredit = lines.stream().map(l -> l.getCredit()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(totalDebit).isEqualByComparingTo(totalCredit);
        assertThat(totalDebit).isEqualByComparingTo("1500.00"); // pas 15000 (10x) : aucune duplication
    }
}
