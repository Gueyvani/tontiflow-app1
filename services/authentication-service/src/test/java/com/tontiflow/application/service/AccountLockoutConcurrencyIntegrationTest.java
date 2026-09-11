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
 * thread, H2 réel) du ralentissement progressif de compte (décision R21-D.5,
 * remplace le verrouillage dur de R21-D.3 — corrige le déni de service par
 * verrouillage, constat D4-01/R21-D.4) — même patron/limite documentée que
 * {@code ContributionConcurrencyIntegrationTest} (financial-service),
 * {@code LedgerConcurrencyIntegrationTest}, {@code RoundConcurrencyIntegrationTest}
 * (tontine-service).
 *
 * <p><b>Limite explicitement documentée</b> : ce test s'exécute contre H2
 * (configuration {@code application-test.yml}), pas contre un PostgreSQL réel
 * — aucun Testcontainers/PostgreSQL n'existe nulle part dans ce dépôt pour ce
 * type de scénario, H2-avec-vrais-threads étant le patron déjà établi. Il
 * prouve donc l'atomicité de {@link AuthAccountRepository#registerFailedAttempt}
 * sous ce moteur — pas littéralement sous PostgreSQL. Le mécanisme (verrou de
 * ligne pris par un {@code UPDATE} unique, sans lecture Java intermédiaire)
 * repose sur une garantie standard partagée par les deux moteurs sous READ
 * COMMITTED, mais ceci reste une inférence, non une preuve directe contre
 * PostgreSQL.</p>
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
    // (failed_attempts=0, aucun delai). Avec un UPDATE atomique (chaque exécution
    // concurrente sérialisée par le verrou de ligne pris par l'UPDATE lui-même), les
    // tentatives s'enregistrent en séquence stricte 1,2,3 - à la 3e, un délai de 2s
    // s'active (table de délai R21-D.5) et TOUTES les tentatives suivantes, arrivant en
    // quelques millisecondes (donc bien avant l'expiration de ce délai), deviennent des
    // no-op (WHERE guard). Le compteur final doit être EXACTEMENT 3 : ni moins (preuve de
    // l'absence de lost update — c'était le bug corrigé en R21-D.3), ni plus (preuve que
    // le guard stoppe bien les incréments une fois le délai actif).
    @Test
    void authenticate_tenConcurrentWrongPasswordAttempts_noLostUpdate_stopsExactlyWhenDelayActivates() throws Exception {
        String email = "concurrency1@tontiflow.test";
        authAccountService.createAccount(email, TEST_PASSWORD);
        UUID accountId = authAccountRepository.findByEmail(email).orElseThrow().getId();

        runConcurrentWrongPasswordAttempts(email, 10);

        AuthAccount reloaded = authAccountRepository.findById(accountId).orElseThrow();
        assertThat(reloaded.getFailedAttempts()).isEqualTo(3);
        assertThat(reloaded.getNextAttemptAllowedAt()).isAfter(Instant.now());
    }

    // SCÉNARIO 2 — palier : compte préparé à failed_attempts=2 (juste avant le premier
    // palier avec délai), 4 tentatives concurrentes avec mauvais mot de passe. La première
    // à committer atteint 3 échecs et active le délai de 2s ; le WHERE guard garantit que
    // les suivantes, concurrentes, deviennent des no-op.
    @Test
    void authenticate_concurrentAttemptsFromTwoFailures_activatesDelayExactlyOnce() throws Exception {
        String email = "concurrency2@tontiflow.test";
        authAccountService.createAccount(email, TEST_PASSWORD);
        AuthAccount account = authAccountRepository.findByEmail(email).orElseThrow();
        account.setFailedAttempts(2);
        account.setLastFailedLoginAt(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        authAccountRepository.save(account);
        UUID accountId = account.getId();

        runConcurrentWrongPasswordAttempts(email, 4);

        AuthAccount reloaded = authAccountRepository.findById(accountId).orElseThrow();
        assertThat(reloaded.getFailedAttempts()).isEqualTo(3);
        assertThat(reloaded.getNextAttemptAllowedAt()).isAfter(Instant.now());
    }

    // SCÉNARIO 3 — délai actif : compte déjà dans une fenêtre de ralentissement
    // (failed_attempts=6, nextAttemptAllowedAt dans le futur, palier plafond 30s), 8
    // tentatives concurrentes avec mauvais mot de passe. Toutes doivent être refusées
    // (InvalidCredentialsException, 401 générique), et — c'est le point testé ici —
    // failed_attempts et nextAttemptAllowedAt doivent rester STRICTEMENT inchangés : le
    // WHERE guard de registerFailedAttempt exclut toute ligne dont le délai est encore
    // actif, donc aucun incrément ni prolongation, même sous accès concurrent massif.
    @Test
    void authenticate_concurrentAttemptsWhileDelayActive_neverExtendDelay_neverIncrementCounter() throws Exception {
        String email = "concurrency3@tontiflow.test";
        authAccountService.createAccount(email, TEST_PASSWORD);
        AuthAccount account = authAccountRepository.findByEmail(email).orElseThrow();
        Instant nextAttemptAllowedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS).plus(Duration.ofSeconds(30));
        account.setFailedAttempts(6);
        account.setNextAttemptAllowedAt(nextAttemptAllowedAt);
        authAccountRepository.save(account);
        UUID accountId = account.getId();

        runConcurrentWrongPasswordAttempts(email, 8);

        AuthAccount reloaded = authAccountRepository.findById(accountId).orElseThrow();
        assertThat(reloaded.getFailedAttempts()).isEqualTo(6);
        assertThat(reloaded.getNextAttemptAllowedAt()).isEqualTo(nextAttemptAllowedAt);
    }

    // SCÉNARIO 4 — succès concurrent avec échec, PREUVE DÉCISIVE de la correction D4-01
    // sous concurrence réelle : le compte est préparé PROFONDÉMENT dans un délai actif
    // (failed_attempts=10, nextAttemptAllowedAt à +30s, le palier plafond) - le pire cas
    // possible pour l'ancien mécanisme (verrouillage dur, R21-D.3). 1 thread avec le BON
    // mot de passe est lancé en parallèle de 3 threads avec un mauvais mot de passe.
    // Contrairement à R21-D.3, le succès n'a AUCUNE condition (voir
    // AuthAccountService.authenticate) : il doit donc réussir à 100% des exécutions, quel
    // que soit l'ordre de commit réel des threads concurrents.
    @Test
    void authenticate_concurrentSuccessDeepInActiveDelay_successAlwaysWinsImmediately() throws Exception {
        String email = "concurrency4@tontiflow.test";
        authAccountService.createAccount(email, TEST_PASSWORD);
        AuthAccount account = authAccountRepository.findByEmail(email).orElseThrow();
        account.setFailedAttempts(10);
        account.setNextAttemptAllowedAt(Instant.now().truncatedTo(ChronoUnit.MILLIS).plus(Duration.ofSeconds(30)));
        authAccountRepository.save(account);
        UUID accountId = account.getId();

        int failureThreads = 3;
        int totalThreads = failureThreads + 1;
        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch readyLatch = new CountDownLatch(totalThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger unexpectedDenialCount = new AtomicInteger();

        List<Callable<Void>> tasks = new ArrayList<>();
        tasks.add(() -> {
            readyLatch.countDown();
            startLatch.await(5, TimeUnit.SECONDS);
            try {
                authAccountService.authenticate(email, TEST_PASSWORD);
                successCount.incrementAndGet();
            } catch (InvalidCredentialsException e) {
                unexpectedDenialCount.incrementAndGet();
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

        // Garantie forte et deterministe (contrairement a R21-D.3) : le succes n'a AUCUNE
        // condition, il gagne donc TOUJOURS, meme profondement dans un delai actif.
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(unexpectedDenialCount.get()).isEqualTo(0);

        // Non deterministe par construction (ordre de commit reel des echecs concurrents,
        // avant ou apres le succes) mais borne : jamais negatif, jamais au-dela du nombre
        // d'echecs possibles - preuve d'absence d'etat "deteriore" (torn), meme si le
        // succes n'a pas ete le tout dernier a committer.
        AuthAccount reloaded = authAccountRepository.findById(accountId).orElseThrow();
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
                        // attendu : mauvais mot de passe (ou delai en cours - meme exception).
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
