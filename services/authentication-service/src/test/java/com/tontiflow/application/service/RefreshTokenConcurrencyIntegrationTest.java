package com.tontiflow.application.service;

import com.tontiflow.application.exception.RefreshTokenReuseDetectedException;
import com.tontiflow.domain.model.RefreshToken;
import com.tontiflow.infrastructure.repository.RefreshTokenRepository;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test de concurrence RÉELLE (vrais threads, vraie transaction Spring par
 * thread, H2 réel) de la consommation atomique du Refresh Token (décision
 * R21-D.6, constat D4-02/R21-D.4) — même patron/limite documentée que
 * {@code ContributionConcurrencyIntegrationTest} (financial-service),
 * {@code AccountLockoutConcurrencyIntegrationTest} (authentication-service).
 *
 * <p><b>Limite explicitement documentée</b> : ce test s'exécute contre H2
 * (configuration {@code application-test.yml}), pas contre un PostgreSQL réel
 * — aucun Testcontainers/PostgreSQL n'existe nulle part dans ce dépôt pour ce
 * type de scénario, H2-avec-vrais-threads étant le patron déjà établi. Il
 * prouve donc l'atomicité de {@link RefreshTokenRepository#consumeIfActive}
 * sous ce moteur — pas littéralement sous PostgreSQL. Le mécanisme (verrou de
 * ligne pris par un {@code UPDATE} unique, sans lecture Java intermédiaire)
 * repose sur une garantie standard partagée par les deux moteurs sous READ
 * COMMITTED, mais ceci reste une inférence, non une preuve directe contre
 * PostgreSQL.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(JwtTestSecurityConfiguration.class)
class RefreshTokenConcurrencyIntegrationTest {

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    // 10 threads presentent EXACTEMENT le meme refresh token, simultanement. Avant la
    // correction R21-D.6, la sequence "lecture revokedAt puis mutation differee au commit"
    // permettait a plusieurs threads de tous lire revokedAt=null et de tous reussir leur
    // rotation. Avec consumeIfActive() (UPDATE atomique et conditionnel, serialise par le
    // verrou de ligne pris par l'UPDATE lui-meme), au plus UNE seule execution peut
    // affecter la ligne : exactement 1 succes, 9 echecs de reutilisation - jamais un autre
    // partage, quel que soit l'ordre reel d'execution des threads.
    @Test
    void rotate_tenConcurrentRequestsWithSameToken_exactlyOneSucceeds_nineDetectedAsReuse() throws Exception {
        UUID accountId = UUID.randomUUID();
        RefreshToken issued = refreshTokenService.issue(accountId);
        String rawToken = issued.getRawToken();
        UUID familyId = issued.getFamilyId();
        UUID originalTokenId = issued.getId();

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger reuseDetectedCount = new AtomicInteger();
        AtomicInteger unexpectedCount = new AtomicInteger();
        AtomicReference<RefreshToken> successfulRotation = new AtomicReference<>();

        List<Callable<Void>> tasks = IntStream.range(0, threadCount)
                .<Callable<Void>>mapToObj(i -> () -> {
                    readyLatch.countDown();
                    startLatch.await(5, TimeUnit.SECONDS);
                    try {
                        RefreshToken rotated = refreshTokenService.rotate(rawToken);
                        successfulRotation.set(rotated);
                        successCount.incrementAndGet();
                    } catch (RefreshTokenReuseDetectedException expected) {
                        reuseDetectedCount.incrementAndGet();
                    } catch (Exception unexpected) {
                        unexpectedCount.incrementAndGet();
                    }
                    return null;
                })
                .toList();

        List<Future<Void>> futures = executor.invokeAll(tasks);
        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();
        for (Future<Void> future : futures) {
            future.get(10, TimeUnit.SECONDS); // aucune exception INATTENDUE ne doit fuiter du Callable lui-meme
        }
        executor.shutdown();

        assertThat(unexpectedCount.get()).isZero();
        // "1 seule rotation reussie" : exactement un appel a rotate() retourne sans lever
        // d'exception - garanti structurellement par l'UPDATE atomique consumeIfActive
        // (au plus une execution peut affecter la ligne), independamment de l'ordre reel.
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(reuseDetectedCount.get()).isEqualTo(9);
        assertThat(successfulRotation.get()).isNotNull();
        assertThat(successfulRotation.get().getFamilyId()).isEqualTo(familyId);
        assertThat(successfulRotation.get().getRawToken()).isNotBlank();

        // L'ancien token est bien revoque (consomme par l'unique gagnant).
        RefreshToken originalReloaded = refreshTokenRepository.findById(originalTokenId).orElseThrow();
        assertThat(originalReloaded.getRevokedAt()).isNotNull();

        // NOTE (comportement pre-existant, non modifie par R21-D.6) : chacune des 9
        // detections de reutilisation appelle revokeFamily(familyId, ...) par defense en
        // profondeur (signal de vol probable) - cela peut revoquer, apres coup, jusqu'au
        // token issu de l'unique rotation reussie si un tel appel commite APRES sa
        // creation. Ce n'est pas une regression de l'atomicite (une seule rotation a bien
        // ete creee, cf. successCount==1 ci-dessus) : c'est la consequence assumee et deja
        // existante de la reponse "revocation de toute la lignee" au moindre signal de
        // reutilisation, meme concurrente. Aucune assertion n'est donc faite ici sur le
        // nombre de tokens ENCORE actifs dans la famille apres coup.
    }

    // Deux familles/tokens totalement independants ne doivent jamais s'entraver : la
    // protection est scoping-minimale (par id de token), pas un verrou global.
    @Test
    void rotate_twoIndependentTokens_bothSucceedIndependently() {
        RefreshToken tokenA = refreshTokenService.issue(UUID.randomUUID());
        RefreshToken tokenB = refreshTokenService.issue(UUID.randomUUID());

        RefreshToken rotatedA = refreshTokenService.rotate(tokenA.getRawToken());
        RefreshToken rotatedB = refreshTokenService.rotate(tokenB.getRawToken());

        assertThat(rotatedA.getFamilyId()).isEqualTo(tokenA.getFamilyId());
        assertThat(rotatedB.getFamilyId()).isEqualTo(tokenB.getFamilyId());
        assertThat(rotatedA.getRawToken()).isNotBlank();
        assertThat(rotatedB.getRawToken()).isNotBlank();

        RefreshToken originalAReloaded = refreshTokenRepository.findById(tokenA.getId()).orElseThrow();
        RefreshToken originalBReloaded = refreshTokenRepository.findById(tokenB.getId()).orElseThrow();
        assertThat(originalAReloaded.getRevokedAt()).isNotNull();
        assertThat(originalBReloaded.getRevokedAt()).isNotNull();
    }
}
