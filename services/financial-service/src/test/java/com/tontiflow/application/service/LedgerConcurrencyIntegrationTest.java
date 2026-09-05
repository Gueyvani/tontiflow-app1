package com.tontiflow.application.service;

import com.tontiflow.domain.enums.Currency;
import com.tontiflow.domain.enums.FinancialAccountType;
import com.tontiflow.domain.model.FinancialAccount;
import com.tontiflow.domain.model.JournalEntry;
import com.tontiflow.infrastructure.repository.JournalEntryRepository;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test de concurrence RÉELLE (vrais threads, vraie transaction Spring par
 * thread, H2 réel, aucun mock) de l'idempotence de {@link LedgerService}
 * (décision R2, §12/§18) — même patron que {@code
 * RoundConcurrencyIntegrationTest} (tontine-service).
 *
 * <p><b>Limite explicitement documentée</b> : H2, pas PostgreSQL (le SGBD
 * réel de production) — la preuve sur PostgreSQL réel de la contrainte
 * {@code UNIQUE(idempotency_key)} et de son comportement sous concurrence
 * réelle est apportée séparément (validation ponctuelle temporaire,
 * méthodologie déjà établie en Phases O5/P1/Q2), non par ce test permanent.
 * Ce test-ci prouve que la logique applicative (capture de {@code
 * DataIntegrityViolationException} + re-consultation) se comporte
 * correctement sous contention réelle, indépendamment du SGBD.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(JwtTestSecurityConfiguration.class)
class LedgerConcurrencyIntegrationTest {

    @Autowired
    private LedgerService ledgerService;
    @Autowired
    private JournalEntryRepository journalEntryRepository;

    // TEST concurrence (§18/§22.15) : dix threads soumettent SIMULTANÉMENT
    // la même idempotencyKey - une seule JournalEntry doit exister au final,
    // aucune exception ne doit fuiter vers l'appelant.
    @Test
    void record_withSameIdempotencyKeySubmittedConcurrently_createsExactlyOneJournalEntry() throws Exception {
        FinancialAccount accountA = ledgerService.getOrCreateAccount(9001L, FinancialAccountType.TONTINE, Currency.MRU);
        FinancialAccount accountB = ledgerService.getOrCreateAccount(9002L, FinancialAccountType.MEMBER, Currency.MRU);

        String idempotencyKey = "concurrent-contribution:9001:9002";
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Callable<JournalEntry>> tasks = java.util.stream.IntStream.range(0, threadCount)
                .<Callable<JournalEntry>>mapToObj(i -> () -> {
                    readyLatch.countDown();
                    startLatch.await(5, TimeUnit.SECONDS);
                    return ledgerService.record(
                            "contribution:9001:9002", "CONTRIBUTION", idempotencyKey, "concurrence reelle",
                            Currency.MRU,
                            List.of(
                                    new PostingLine(accountA.getId(), new BigDecimal("100.00"), BigDecimal.ZERO),
                                    new PostingLine(accountB.getId(), BigDecimal.ZERO, new BigDecimal("100.00"))
                            ));
                })
                .toList();

        List<Future<JournalEntry>> futures = executor.invokeAll(tasks);
        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        for (Future<JournalEntry> future : futures) {
            // Aucune exception ne doit fuiter : chaque thread reçoit soit
            // l'écriture qu'il a créée, soit celle créée par un concurrent.
            assertThat(future.get(10, TimeUnit.SECONDS)).isNotNull();
        }
        executor.shutdown();

        // Correction R3 : le contexte Spring (donc H2) peut être partagé entre
        // classes de test ayant une configuration identique (ex. la classe
        // ContributionConcurrencyIntegrationTest introduite en Phase R3) - ne
        // jamais compter globalement, toujours filtrer sur la clé métier
        // propre à ce scénario.
        long matchingEntries = journalEntryRepository.findAll().stream()
                .filter(e -> idempotencyKey.equals(e.getIdempotencyKey()))
                .count();
        assertThat(matchingEntries).isEqualTo(1);
    }
}
