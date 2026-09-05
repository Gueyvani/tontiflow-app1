package com.tontiflow.application.service;

import com.tontiflow.domain.enums.RoundStatus;
import com.tontiflow.domain.model.TontineRound;
import com.tontiflow.infrastructure.repository.TontineConfigRepository;
import com.tontiflow.infrastructure.repository.TontineRoundRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Reprise automatique des rounds {@code BLOCKED} (décision P1) : rescane
 * périodiquement les rounds bloqués par une {@code TontineConfig} absente et
 * retente leur complétion via {@link RoundCompletionScheduler}.
 *
 * <p>Même patron que {@link SuspendedRoundRetryScheduler} (décision S1b) :
 * scheduler dédié à fréquence réduite, verrou pessimiste + revalidation de
 * l'état avant traitement, transaction isolée par round, gestion d'erreur
 * isolée. Aucun {@code callerUserId} nécessaire : déclenchement système,
 * comme les deux autres schedulers. Ne traite que le statut {@code BLOCKED}
 * — aucun recouvrement avec {@link RoundCompletionScheduler} (qui ne traite
 * que {@code ASSIGNED}) ni {@link SuspendedRoundRetryScheduler} (qui ne
 * traite que {@code SUSPENDED}).</p>
 *
 * <p>Fréquence : 5 minutes par défaut, configurable via {@code
 * tontine.round.blocked-retry-interval-ms} — détail technique, pas une
 * décision métier.</p>
 */
@Component
public class BlockedRoundRetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(BlockedRoundRetryScheduler.class);

    private final TontineRoundRepository roundRepository;
    private final TontineConfigRepository configRepository;
    private final RoundCompletionScheduler completionScheduler;

    /**
     * Référence différée vers le proxy Spring de ce bean — même nécessité
     * que pour {@link RoundCompletionScheduler#self} : une auto-invocation
     * directe de {@link #retryOneBlockedRound} contournerait le proxy AOP et
     * désactiverait {@code @Transactional}.
     */
    private BlockedRoundRetryScheduler self;

    public BlockedRoundRetryScheduler(TontineRoundRepository roundRepository,
                                       TontineConfigRepository configRepository,
                                       RoundCompletionScheduler completionScheduler) {
        this.roundRepository = roundRepository;
        this.configRepository = configRepository;
        this.completionScheduler = completionScheduler;
    }

    @Autowired
    public void setSelf(@Lazy BlockedRoundRetryScheduler self) {
        this.self = self;
    }

    @Scheduled(fixedDelayString = "${tontine.round.blocked-retry-interval-ms:300000}")
    public void retryBlockedRounds() {
        List<TontineRound> blocked = roundRepository.findByStatus(RoundStatus.BLOCKED);

        for (TontineRound round : blocked) {
            try {
                self.retryOneBlockedRound(round.getId());
            } catch (Exception e) {
                log.error("Échec de la reprise automatique du round BLOCKED {} : {}",
                        round.getId(), e.getMessage(), e);
            }
        }
    }

    /**
     * Traite un round {@code BLOCKED} individuellement, dans sa propre
     * transaction — l'échec d'un round n'affecte pas le traitement des
     * autres. Verrouille le round et revérifie son statut avant traitement,
     * pour se protéger d'une exécution concurrente (ex. correction manuelle
     * de la configuration exécutée entre le scan et le traitement).
     *
     * <p>Réutilise directement {@link RoundCompletionScheduler#completeNow}
     * (même méthode que le chemin de complétion normal) une fois la
     * configuration confirmée disponible — aucune logique de complétion
     * dupliquée. Si elle est toujours absente, le round reste {@code
     * BLOCKED} sans nouvelle écriture ni nouveau log (déjà loggé au passage
     * qui l'a initialement bloqué, évitant un bruit de log répété toutes les
     * 5 minutes tant que l'anomalie n'est pas corrigée).</p>
     */
    @Transactional
    void retryOneBlockedRound(Long roundId) {
        TontineRound round = roundRepository.findByIdForUpdate(roundId)
                .orElseThrow(() -> new IllegalArgumentException("Round non trouvé"));

        if (round.getStatus() != RoundStatus.BLOCKED) {
            return; // état déjà modifié entre-temps (concurrence) : rien à faire
        }

        var config = configRepository.findByTontineId(round.getTontineId());
        if (config.isEmpty()) {
            return; // toujours absente : reste BLOCKED, déjà loggé au blocage initial
        }

        completionScheduler.completeNow(round, config.get());
    }
}
