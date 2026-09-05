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
 * Test de concurrence RÉELLE (vrais threads, vraie transaction Spring par
 * thread, H2 réel) du flux de contribution complet (décision R3, §17/§27) —
 * même patron/limite documentée que {@code LedgerConcurrencyIntegrationTest}
 * (R2) et {@code RoundConcurrencyIntegrationTest} (tontine-service).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(JwtTestSecurityConfiguration.class)
class ContributionConcurrencyIntegrationTest {

    @Autowired
    private ContributionService contributionService;
    @Autowired
    private JournalEntryRepository journalEntryRepository;
    @Autowired
    private LedgerLineRepository ledgerLineRepository;

    // TEST concurrence (§17/§27/§45) : 10 threads soumettent SIMULTANÉMENT
    // la même contribution (mêmes tontineId/roundId/memberId) - une seule
    // JournalEntry et exactement 2 LedgerLine doivent exister au final,
    // aucune exception ne doit fuiter, aucun double débit/crédit.
    @Test
    void recordContribution_submittedConcurrentlyBySameIdentifiers_createsExactlyOneEntry() throws Exception {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Callable<JournalEntry>> tasks = IntStream.range(0, threadCount)
                .<Callable<JournalEntry>>mapToObj(i -> () -> {
                    readyLatch.countDown();
                    startLatch.await(5, TimeUnit.SECONDS);
                    return contributionService.recordContribution(
                            30L, 40L, 500L, new BigDecimal("1000.00"), Currency.MRU);
                })
                .toList();

        List<Future<JournalEntry>> futures = executor.invokeAll(tasks);
        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        for (Future<JournalEntry> future : futures) {
            assertThat(future.get(10, TimeUnit.SECONDS)).isNotNull(); // aucune exception non controlee
        }
        executor.shutdown();

        // Le contexte Spring (donc H2) peut etre partage entre classes de test
        // ayant une configuration identique (ex. LedgerConcurrencyIntegrationTest) :
        // ne jamais compter globalement, toujours filtrer sur la cle metier
        // propre a ce scenario.
        String idempotencyKey = "contribution:30:40:500";
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
        assertThat(totalDebit).isEqualByComparingTo("1000.00"); // pas 10000 (10x) : aucune duplication
    }
}
