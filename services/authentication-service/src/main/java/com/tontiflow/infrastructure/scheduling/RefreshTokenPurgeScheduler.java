package com.tontiflow.infrastructure.scheduling;

import com.tontiflow.application.service.RefreshTokenPurgeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Déclenche périodiquement la purge des Refresh Tokens (décision F-2). Seule tâche planifiée de
 * {@code authentication-service} : {@link EnableScheduling} est porté par cette classe et n'est donc
 * actif que si la purge l'est ({@code refresh-token.purge.enabled}, vrai par défaut, faux sous le
 * profil {@code test}).
 *
 * <p>{@code fixedDelay} : la passe suivante démarre {@code interval-ms} après la fin de la
 * précédente, donc jamais deux passes simultanées sur une même instance. Une exception est journalisée
 * (classe et message, jamais de jeton) et n'interrompt pas la planification : la passe suivante
 * reprend. Journalisation : un résumé par passe ayant supprimé des lignes, rien sinon.</p>
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "refresh-token.purge.enabled", havingValue = "true", matchIfMissing = true)
public class RefreshTokenPurgeScheduler {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenPurgeScheduler.class);

    private final RefreshTokenPurgeService purgeService;

    public RefreshTokenPurgeScheduler(RefreshTokenPurgeService purgeService) {
        this.purgeService = purgeService;
    }

    @Scheduled(fixedDelayString = "${refresh-token.purge.interval-ms:3600000}")
    public void purge() {
        long start = System.nanoTime();
        try {
            RefreshTokenPurgeService.PurgeResult result = purgeService.purgeExpiredFamilies();
            if (result.deletedRows() > 0) {
                log.info("refresh_token_purge : deletedRows={} batches={} durationMs={}",
                        result.deletedRows(), result.batches(), (System.nanoTime() - start) / 1_000_000);
            }
        } catch (RuntimeException e) {
            log.warn("refresh_token_purge_failed : {} durationMs={}", e.toString(),
                    (System.nanoTime() - start) / 1_000_000);
        }
    }
}
