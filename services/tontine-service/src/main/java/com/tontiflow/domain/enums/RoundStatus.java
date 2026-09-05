package com.tontiflow.domain.enums;

public enum RoundStatus {
    PLANNED,
    OPEN,
    ELIGIBLE,
    ASSIGNED,
    PAID,
    COMPLETED,
    SUSPENDED,
    CANCELLED,

    /**
     * Round arrivé à échéance ({@code ASSIGNED} + date de fin dépassée) dont
     * la complétion automatique a été empêchée par une anomalie de
     * configuration (décision P1 : {@code TontineConfig} absente), et non
     * par une décision métier. Ajouté en fin d'enum — mapping JPA
     * {@code ORDINAL} (aucun {@code @Enumerated} sur {@code
     * TontineRound.status}, cf. migration V3) : ne jamais réordonner les
     * valeurs existantes (0-7 doivent rester identiques). Retraité
     * périodiquement par {@link BlockedRoundRetryScheduler}.
     */
    BLOCKED
}


