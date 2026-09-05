package com.tontiflow.application.service;

import com.tontiflow.domain.enums.RoundStatus;
import com.tontiflow.domain.model.TontineConfig;
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

import java.util.Comparator;
import java.util.List;

/**
 * Reprise des tontines dont le dernier round est {@code COMPLETED} sans
 * qu'aucun round {@code PLANNED} suivant n'ait été créé (décision S3b,
 * option S3b-1 validée : retry périodique à intervalle fixe, sans backoff
 * exponentiel persisté, sans compteur de tentatives, sans nouvelle colonne
 * ni table).
 *
 * <p><b>Sans état persisté</b> : la condition « dernier round COMPLETED sans
 * PLANNED » est recalculée depuis la base à chaque exécution — aucune
 * information de retry n'est perdue après un redémarrage du service, car il
 * n'y a rien à perdre : le prochain passage réévalue simplement l'état réel.
 * Idempotent par construction : dès qu'un round {@code PLANNED} existe pour
 * la tontine, celle-ci ne correspond plus au critère et n'est plus
 * retraitée.</p>
 *
 * <p>Ne modifie pas {@link RoundCompletionScheduler}, qui reste seul
 * responsable de la transition {@code ASSIGNED} → {@code COMPLETED}. Ce
 * scheduler n'agit qu'après coup, sur des tontines déjà {@code COMPLETED} —
 * aucun recouvrement d'ensemble traité entre les deux jobs.</p>
 *
 * <p>Fréquence : 5 minutes par défaut (décision validée), configurable via
 * {@code tontine.round.orphaned-completed-retry-interval-ms}.</p>
 */
@Component
public class OrphanedCompletedRoundRetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(OrphanedCompletedRoundRetryScheduler.class);

    private final TontineRoundRepository roundRepository;
    private final TontineConfigRepository configRepository;
    private final TontineRoundApplicationService roundApplicationService;

    /**
     * Référence différée vers le proxy Spring de ce bean — même nécessité
     * que pour {@link RoundCompletionScheduler#self} : une auto-invocation
     * directe de {@link #retryOneTontine} contournerait le proxy AOP et
     * désactiverait {@code @Transactional}.
     */
    private OrphanedCompletedRoundRetryScheduler self;

    public OrphanedCompletedRoundRetryScheduler(TontineRoundRepository roundRepository,
                                                 TontineConfigRepository configRepository,
                                                 TontineRoundApplicationService roundApplicationService) {
        this.roundRepository = roundRepository;
        this.configRepository = configRepository;
        this.roundApplicationService = roundApplicationService;
    }

    @Autowired
    public void setSelf(@Lazy OrphanedCompletedRoundRetryScheduler self) {
        this.self = self;
    }

    @Scheduled(fixedDelayString = "${tontine.round.orphaned-completed-retry-interval-ms:300000}")
    public void retryOrphanedCompletedRounds() {
        List<Long> tontineIds = roundRepository.findDistinctTontineIdsByStatus(RoundStatus.COMPLETED);

        for (Long tontineId : tontineIds) {
            try {
                self.retryOneTontine(tontineId);
            } catch (Exception e) {
                log.error("Échec de la reprise du round orphelin pour la tontine {} : {}",
                        tontineId, e.getMessage(), e);
            }
        }
    }

    /**
     * Traite une tontine individuellement, dans sa propre transaction —
     * l'échec sur une tontine n'affecte pas le traitement des autres.
     * Verrouille le dernier round connu et revalide l'état complet après
     * acquisition, pour se protéger d'une exécution concurrente (ex.
     * plusieurs instances du service, ou une reprise SUSPENDED concurrente).
     */
    @Transactional
    void retryOneTontine(Long tontineId) {
        List<TontineRound> rounds = roundRepository.findByTontineId(tontineId);
        if (rounds.isEmpty()) {
            return;
        }

        TontineRound latest = rounds.stream()
                .max(Comparator.comparingInt(TontineRound::getRoundNumber))
                .orElseThrow();

        roundRepository.findByIdForUpdate(latest.getId())
                .orElseThrow(() -> new IllegalArgumentException("Round non trouvé"));

        // Revalidation complète après verrou.
        List<TontineRound> roundsAfterLock = roundRepository.findByTontineId(tontineId);
        boolean plannedAlreadyExists = roundsAfterLock.stream()
                .anyMatch(r -> r.getStatus() == RoundStatus.PLANNED);
        if (plannedAlreadyExists) {
            return; // déjà repris entre-temps
        }

        TontineRound latestAfterLock = roundsAfterLock.stream()
                .max(Comparator.comparingInt(TontineRound::getRoundNumber))
                .orElseThrow();
        if (latestAfterLock.getStatus() != RoundStatus.COMPLETED) {
            return; // n'est plus orphelin (état modifié entre-temps)
        }

        TontineConfig config = configRepository.findByTontineId(tontineId)
                .orElseThrow(() -> new IllegalStateException(
                        "Configuration introuvable pour la tontine " + tontineId));

        try {
            roundApplicationService.createRoundForTontine(
                    tontineId, config, latestAfterLock.getRoundNumber() + 1);
        } catch (IllegalStateException e) {
            log.warn("Round suivant non créé pour la tontine {} (retry) : {}", tontineId, e.getMessage());
        }
    }
}
