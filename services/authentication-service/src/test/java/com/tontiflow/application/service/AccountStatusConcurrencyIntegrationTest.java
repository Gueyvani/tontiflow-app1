package com.tontiflow.application.service;

import com.tontiflow.application.exception.InvalidAccountStatusTransitionException;
import com.tontiflow.domain.enums.AccountStatus;
import com.tontiflow.domain.model.AccountStatusChange;
import com.tontiflow.domain.model.AuthAccount;
import com.tontiflow.infrastructure.repository.AccountStatusChangeRepository;
import com.tontiflow.infrastructure.repository.AuthAccountRepository;
import com.tontiflow.infrastructure.security.jwt.JwtTestSecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test de concurrence RÉELLE (vrais threads, vraie transaction Spring par
 * thread, H2 réel) du changement administratif de statut de compte
 * (décision R21-RD, D2/D3/D7) — même patron ("ready/start latch") que
 * {@link AccountLockoutConcurrencyIntegrationTest}.
 *
 * <p><b>Limite explicitement documentée</b> : ce test s'exécute contre H2
 * (configuration {@code application-test.yml}), pas contre un PostgreSQL réel
 * — même limite déjà documentée pour {@code AccountLockoutConcurrencyIntegrationTest}.
 * Il prouve l'atomicité de {@link AuthAccountRepository#transitionStatusIfAllowed}
 * sous ce moteur, par inférence (verrou de ligne pris par un {@code UPDATE}
 * unique sous READ COMMITTED) et non par preuve directe contre PostgreSQL.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(JwtTestSecurityConfiguration.class)
class AccountStatusConcurrencyIntegrationTest {

    private static final String TEST_PASSWORD = "S3cur3-Test-Passw0rd!";

    @Autowired
    private AuthAccountService authAccountService;

    @Autowired
    private AuthAccountRepository authAccountRepository;

    @Autowired
    private AccountStatusChangeRepository accountStatusChangeRepository;

    // SCÉNARIO 1 — transition valide disputée par N acteurs concurrents (ex. deux
    // administrateurs cliquant "verrouiller" au même instant). Avec transitionStatusIfAllowed
    // (UPDATE atomique avec guard) + le patron "course puis relecture" de
    // AuthAccountService.changeAccountStatus, EXACTEMENT un thread gagne l'UPDATE réel ; tous
    // les autres relisent l'état, constatent que la cible est déjà atteinte et retournent un
    // succès idempotent (D3) SANS lever d'exception et SANS écrire de second événement d'audit
    // (D7 : un seul événement pour un seul changement réel).
    @Test
    void changeAccountStatus_concurrentRaceOnValidTransition_exactlyOneAuditEvent_allCallersSucceedWithoutException() throws Exception {
        String email = "status-concurrency1@tontiflow.test";
        authAccountService.createAccount(email, TEST_PASSWORD);
        UUID accountId = authAccountRepository.findByEmail(email).orElseThrow().getId();

        int threadCount = 8;
        List<String> reasons = IntStream.range(0, threadCount)
                .mapToObj(i -> "Motif administrateur concurrent #" + i)
                .toList();

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger unexpectedFailureCount = new AtomicInteger();

        List<Callable<Void>> tasks = IntStream.range(0, threadCount)
                .<Callable<Void>>mapToObj(i -> () -> {
                    readyLatch.countDown();
                    startLatch.await(5, TimeUnit.SECONDS);
                    try {
                        authAccountService.changeAccountStatus(accountId, AccountStatus.LOCKED, reasons.get(i), UUID.randomUUID());
                    } catch (InvalidAccountStatusTransitionException unexpected) {
                        unexpectedFailureCount.incrementAndGet();
                    }
                    return null;
                })
                .toList();

        List<Future<Void>> futures = executor.invokeAll(tasks);
        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();
        for (Future<Void> future : futures) {
            future.get(10, TimeUnit.SECONDS); // aucune exception INATTENDUE (hors InvalidAccountStatusTransitionException capturee) ne doit fuiter
        }
        executor.shutdown();

        // D2 sous concurrence : toujours exactement un gagnant, jamais un echec du a la course.
        assertThat(unexpectedFailureCount.get()).isEqualTo(0);

        AuthAccount reloaded = authAccountRepository.findById(accountId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AccountStatus.LOCKED);

        // D7 sous concurrence : un seul changement reel, donc un seul evenement d'audit -
        // jamais N (un par thread), ce qui prouverait une desactivation du guard atomique.
        List<AccountStatusChange> events = accountStatusChangeRepository.findByAccountId(accountId);
        assertThat(events).hasSize(1);
        assertThat(reasons).contains(events.get(0).getReason());
    }

    // SCÉNARIO 2 — transition INTERDITE (DISABLED -> LOCKED, decision R21-RD D2) disputee par N
    // threads concurrents. Le WHERE guard de transitionStatusIfAllowed n'autorise jamais cette
    // transition, quel que soit l'ordre reel de commit : TOUS les threads doivent recevoir
    // InvalidAccountStatusTransitionException, le compte doit rester DISABLED, et AUCUN
    // evenement d'audit ne doit etre cree.
    @Test
    void changeAccountStatus_concurrentRaceOnForbiddenTransition_alwaysRejected_zeroAuditEvents() throws Exception {
        String email = "status-concurrency2@tontiflow.test";
        authAccountService.createAccount(email, TEST_PASSWORD);
        UUID accountId = authAccountRepository.findByEmail(email).orElseThrow().getId();
        // Prealable reel (pas de bypass DB) : ACTIVE -> DISABLED via le service, comme le ferait
        // un vrai appel admin.
        authAccountService.changeAccountStatus(accountId, AccountStatus.DISABLED, "Preparation du scenario", UUID.randomUUID());

        int threadCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger rejectedCount = new AtomicInteger();
        AtomicInteger unexpectedSuccessCount = new AtomicInteger();

        List<Callable<Void>> tasks = IntStream.range(0, threadCount)
                .<Callable<Void>>mapToObj(i -> () -> {
                    readyLatch.countDown();
                    startLatch.await(5, TimeUnit.SECONDS);
                    try {
                        authAccountService.changeAccountStatus(accountId, AccountStatus.LOCKED,
                                "Tentative interdite concurrente #" + i, UUID.randomUUID());
                        unexpectedSuccessCount.incrementAndGet();
                    } catch (InvalidAccountStatusTransitionException expected) {
                        rejectedCount.incrementAndGet();
                    }
                    return null;
                })
                .toList();

        List<Future<Void>> futures = executor.invokeAll(tasks);
        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();
        for (Future<Void> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertThat(unexpectedSuccessCount.get()).isEqualTo(0);
        assertThat(rejectedCount.get()).isEqualTo(threadCount);

        AuthAccount reloaded = authAccountRepository.findById(accountId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AccountStatus.DISABLED);

        // Un seul evenement : celui de la preparation ACTIVE -> DISABLED. Aucune tentative
        // interdite, meme concurrente, n'a pu en ajouter un second.
        List<AccountStatusChange> events = accountStatusChangeRepository.findByAccountId(accountId);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getNewStatus()).isEqualTo(AccountStatus.DISABLED);
    }
}
