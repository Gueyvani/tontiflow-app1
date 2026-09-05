package com.tontiflow.application.service;

import com.tontiflow.domain.enums.RoundStatus;
import com.tontiflow.domain.model.TontineRound;
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
 * Reprise automatique des rounds {@code SUSPENDED} (décision S1b) : rescane
 * périodiquement les rounds {@code SUSPENDED} et retente l'éligibilité via
 * {@link TontineRoundApplicationService#retryEligibility}.
 *
 * <p>Aucun {@code callerUserId} n'est nécessaire : le déclenchement est
 * système, comme {@link RoundCompletionScheduler}, qui gère exclusivement la
 * transition {@code ASSIGNED} → {@code COMPLETED} et reste inchangé. Ce
 * scheduler ne traite que le statut {@code SUSPENDED} — aucun recouvrement
 * d'ensemble de rounds entre les deux jobs, donc aucun mécanisme concurrent
 * incohérent (décision S2).</p>
 *
 * <p>Fréquence : 5 minutes par défaut, configurable via {@code
 * tontine.round.suspended-retry-interval-ms} — détail technique, pas une
 * décision métier.</p>
 */
@Component
public class SuspendedRoundRetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(SuspendedRoundRetryScheduler.class);

    private final TontineRoundRepository roundRepository;
    private final TontineRoundApplicationService roundApplicationService;

    /**
     * Référence différée vers le proxy Spring de ce bean — même nécessité
     * que pour {@link RoundCompletionScheduler#self} : une auto-invocation
     * directe de {@link #retryOneSuspendedRound} contournerait le proxy AOP
     * et désactiverait {@code @Transactional}.
     */
    private SuspendedRoundRetryScheduler self;

    public SuspendedRoundRetryScheduler(TontineRoundRepository roundRepository,
                                         TontineRoundApplicationService roundApplicationService) {
        this.roundRepository = roundRepository;
        this.roundApplicationService = roundApplicationService;
    }

    @Autowired
    public void setSelf(@Lazy SuspendedRoundRetryScheduler self) {
        this.self = self;
    }

    @Scheduled(fixedDelayString = "${tontine.round.suspended-retry-interval-ms:300000}")
    public void retrySuspendedRounds() {
        List<TontineRound> suspended = roundRepository.findByStatus(RoundStatus.SUSPENDED);

        for (TontineRound round : suspended) {
            try {
                self.retryOneSuspendedRound(round.getId());
            } catch (Exception e) {
                log.error("Échec de la reprise automatique du round SUSPENDED {} : {}",
                        round.getId(), e.getMessage(), e);
            }
        }
    }

    /**
     * Traite un round {@code SUSPENDED} individuellement, dans sa propre
     * transaction — l'échec d'un round n'affecte pas le traitement des
     * autres. Verrouille le round et revérifie son statut avant modification,
     * pour se protéger d'une exécution concurrente (ex. un {@code
     * assign-beneficiary} manuel exécuté entre-temps).
     */
    @Transactional
    void retryOneSuspendedRound(Long roundId) {
        TontineRound round = roundRepository.findByIdForUpdate(roundId)
                .orElseThrow(() -> new IllegalArgumentException("Round non trouvé"));

        if (round.getStatus() != RoundStatus.SUSPENDED) {
            return; // état déjà modifié entre-temps (concurrence) : rien à faire
        }

        roundApplicationService.retryEligibility(round);
    }
}
