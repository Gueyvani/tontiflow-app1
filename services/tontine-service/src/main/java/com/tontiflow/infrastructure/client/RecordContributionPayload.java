package com.tontiflow.infrastructure.client;

import java.math.BigDecimal;

/**
 * Corps de la requête interne envoyée à {@code financial-service} (décision
 * R3). Délibérément propre à {@code tontine-service} (pas un DTO partagé
 * avec {@code financial-service}) — évite tout couplage de compilation
 * entre les deux services ; {@code currency} reste une chaîne (tontine-service
 * ne modélise aujourd'hui aucune devise propre — seul {@code "MRU"} est
 * utilisé, cf. {@link FinancialServiceClient}).
 */
record RecordContributionPayload(
        Long tontineId,
        Long roundId,
        Long memberId,
        BigDecimal amount,
        String currency
) {
}
