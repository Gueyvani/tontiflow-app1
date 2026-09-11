package com.tontiflow.application.service;

import com.tontiflow.application.exception.InvalidCredentialsException;
import com.tontiflow.domain.model.AuthAccount;
import com.tontiflow.infrastructure.repository.AuthAccountRepository;
import com.tontiflow.infrastructure.security.jwt.JwtTestSecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
 * thread, H2 réel) du verrouillage temporisé de compte (décision R21-D.3 ;
 * correction P1, atomicité) — même patron/limite documentée que
 * {@code ContributionConcurrencyIntegrationTest} (financial-service),
 * {@code LedgerConcurrencyIntegrationTest}, {@code RoundConcurrencyIntegrationTest}
 * (tontine-service).
 *
 * <p><b>Limite explicitement documentée</b> : ce test s'exécute contre H2
 * (configuration {@code application-test.yml}), pas contre un PostgreSQL réel
 * — aucun Testcontainers/PostgreSQL n'existe nulle part dans ce dépôt pour ce
 * type de scénario, H2-avec-vrais-threads étant le patron déjà établi. Il
 * prouve donc l'atomicité de {@link AuthAccountRepository#registerFailedAttempt}
 * / {@link AuthAccountRepository#resetFailedAttemptsIfNotLocked} sous ce
 * moteur — pas littéralement sous PostgreSQL. Le mécanisme (verrou de ligne
 * pris par un {@code UPDATE} unique, sans lecture Java intermédiaire) repose
 * sur une garantie standard partagée par les deux moteurs sous READ COMMITTED,
 * mais ceci reste une inférence, non une preuve directe contre PostgreSQL.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(JwtTestSecurityConfiguration.class)
class AccountLockoutConcurrencyIntegrationTest {

    private static final String TEST_PASSWORD = "S3cur3-Test-Passw0rd!";
    private static final String WRONG_PASSWORD = "mauvais-mot-de-passe";

    @Autowired
    private AuthAccountService authAccountService;

    @Autowired
    private AuthAccountRepository authAccountRepository;

    // SCÉNARIO 1 — incréments concurrents : 10 threads, mauvais mot de passe, compte propre
    // (failed_attempts=0, locked_until=null). Seuil par defaut = 5
    // (application-test.yml/application.yml, non surchargé ici). Avec un UPDATE atomique
    // (chaque exécution concurrente sérialisée par le verrou de ligne pris par l'UPDATE
    // lui-même), le compteur final doit être EXACTEMENT 5 : ni moins (preuve de l'absence
    // de lost update — c'était le bug corrigé), ni plus (preuve que le WHERE guard stoppe
    // bien les incréments une fois verrouillé).
    @Test
    void authenticate_tenConcurrentWrongPasswordAttempts_noLostUpdate_locksExactlyAtThreshold() throws Exception {
        String email = "concurrency1@tontiflow.test";
        authAccountService.createAccount(email, TEST_PASSWORD);
        UUID accountId = authAccountRepository.findByEmail(email).orElseThrow().getId();

        runConcurrentWrongPasswordAttempts(email, 10);

        AuthAccount reloaded = authAccountRepository.findById(accountId).orElseThrow();
        assertThat(reloaded.getFailedAttempts()).isEqualTo(5);
        assertThat(reloaded.getLockedUntil()).isNotNull();
    }

    // SCÉNARIO 2 — seuil : compte préparé à failed_attempts=4, 4 tentatives concurrentes
    // avec mauvais mot de passe. Au moins une doit franchir le seuil ; le WHERE guard
    // garantit qu'aucune ne peut dépasser durablement au-delà (verrouillage déclenché puis
    // les suivantes deviennent des no-op).
    @Test
    void authenticate_concurrentAttemptsFromFourFailures_reachesThresholdAndLocks() throws Exception {
        String email = "concurrency2@tontiflow.test";
        authAccountService.createAccount(email, TEST_PASSWORD);
        AuthAccount account = authAccountRepository.findByEmail(email).orElseThrow();
        account.setFailedAttempts(4);
        account.setLastFailedLoginAt(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        authAccountRepository.save(account);
        UUID accountId = account.getId();

        runConcurrentWrongPasswordAttempts(email, 4);

        AuthAccount reloaded = authAccountRepository.findById(accountId).orElseThrow();
        assertThat(reloaded.getFailedAttempts()).isGreaterThanOrEqualTo(5);
        assertThat(reloaded.getLockedUntil()).isNotNull();
    }

    // SCÉNARIO 3 — verrou actif : compte déjà verrouillé (failed_attempts=5, locked_until
    // dans le futur), 8 tentatives concurrentes avec mauvais mot de passe. Toutes doivent
    // être refusées (InvalidCredentialsException), et — c'est le point testé ici —
    // failed_attempts et locked_until doivent rester STRICTEMENT inchangés : le WHERE
    // guard de registerFailedAttempt exclut toute ligne déjà verrouillée, donc aucun
    // incrément ni prolongation, même sous accès concurrent massif.
    @Test
    void authenticate_concurrentAttemptsWhileLocked_neverExtendLock_neverIncrementCounter() throws Exception {
        String email = "concurrency3@tontiflow.test";
        authAccountService.createAccount(email, TEST_PASSWORD);
        AuthAccount account = authAccountRepository.findByEmail(email).orElseThrow();
        Instant lockedUntil = Instant.now().truncatedTo(ChronoUnit.MILLIS).plus(Duration.ofMinutes(10));
        account.setFailedAttempts(5);
        account.setLockedUntil(lockedUntil);
        authAccountRepository.save(account);
        UUID accountId = account.getId();

        runConcurrentWrongPasswordAttempts(email, 8);

        AuthAccount reloaded = authAccountRepository.findById(accountId).orElseThrow();
        assertThat(reloaded.getFailedAttempts()).isEqualTo(5);
        assertThat(reloaded.getLockedUntil()).isEqualTo(lockedUntil);
    }

    // SCÉNARIO 4 — succès concurrent avec échec : 1 tentative avec le BON mot de passe et
    // 3 avec un mauvais, en parallèle, sur un compte propre. Avec seulement 3 échecs
    // possibles (< seuil de 5), le compte ne peut JAMAIS se retrouver verrouillé dans ce
    // scénario, quel que soit l'ordre de commit : la tentative correcte doit donc TOUJOURS
    // réussir (garantie forte, déterministe). Ce qui reste non déterministe (par
    // construction, selon l'ordre réel de commit des UPDATE) est la valeur finale de
    // failed_attempts, qui doit rester dans l'intervalle [0, 3] - jamais en dehors, ce qui
    // prouverait un état "déchiré" (torn) plutôt qu'une simple sérialisation valide.
    @Test
    void authenticate_concurrentSuccessAndFailure_successAlwaysWins_neverProducesTornState() throws Exception {
        String email = "concurrency4@tontiflow.test";
        authAccountService.createAccount(email, TEST_PASSWORD);
        UUID accountId = authAccountRepository.findByEmail(email).orElseThrow().getId();

        int failureThreads = 3;
        int totalThreads = failureThreads + 1;
        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch readyLatch = new CountDownLatch(totalThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger unexpectedCount = new AtomicInteger();

        List<Callable<Void>> tasks = new ArrayList<>();
        tasks.add(() -> {
            readyLatch.countDown();
            startLatch.await(5, TimeUnit.SECONDS);
            try {
                authAccountService.authenticate(email, TEST_PASSWORD);
                successCount.incrementAndGet();
            } catch (InvalidCredentialsException e) {
                unexpectedCount.incrementAndGet();
            }
            return null;
        });
        for (int i = 0; i < failureThreads; i++) {
            tasks.add(() -> {
                readyLatch.countDown();
                startLatch.await(5, TimeUnit.SECONDS);
                try {
                    authAccountService.authenticate(email, WRONG_PASSWORD);
                } catch (InvalidCredentialsException expected) {
                    // attendu : mauvais mot de passe.
                }
                return null;
            });
        }

        List<Future<Void>> futures = executor.invokeAll(tasks);
        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();
        for (Future<Void> future : futures) {
            future.get(10, TimeUnit.SECONDS); // aucune exception INATTENDUE ne doit fuiter
        }
        executor.shutdown();

        // Garantie forte et deterministe : avec seulement 3 echecs possibles (< seuil 5),
        // le compte ne peut jamais etre verrouille dans ce scenario -> le bon mot de passe
        // reussit TOUJOURS, quel que soit l'ordre de commit.
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(unexpectedCount.get()).isEqualTo(0);

        AuthAccount reloaded = authAccountRepository.findById(accountId).orElseThrow();
        assertThat(reloaded.getLockedUntil()).isNull();
        // Non deterministe par construction (ordre de commit reel) mais borne : jamais
        // negatif, jamais > le nombre d'echecs possibles - preuve d'absence d'etat dechire.
        assertThat(reloaded.getFailedAttempts()).isBetween(0, failureThreads);
    }

    /**
     * Lance {@code threadCount} tentatives d'authentification concurrentes avec un mauvais
     * mot de passe sur le même compte (patron "ready/start latch" déjà utilisé par
     * {@code ContributionConcurrencyIntegrationTest}), et vérifie qu'aucune n'a levé une
     * exception inattendue.
     */
    private void runConcurrentWrongPasswordAttempts(String email, int threadCount) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Callable<Void>> tasks = IntStream.range(0, threadCount)
                .<Callable<Void>>mapToObj(i -> () -> {
                    readyLatch.countDown();
                    startLatch.await(5, TimeUnit.SECONDS);
                    try {
                        authAccountService.authenticate(email, WRONG_PASSWORD);
                        throw new AssertionError("authenticate() aurait du lever InvalidCredentialsException");
                    } catch (InvalidCredentialsException expected) {
                        // attendu : mauvais mot de passe (ou verrouillage automatique - meme exception).
                    }
                    return null;
                })
                .toList();

        List<Future<Void>> futures = executor.invokeAll(tasks);
        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();
        for (Future<Void> future : futures) {
            future.get(10, TimeUnit.SECONDS); // aucune exception INATTENDUE ne doit fuiter
        }
        executor.shutdown();
    }
}
