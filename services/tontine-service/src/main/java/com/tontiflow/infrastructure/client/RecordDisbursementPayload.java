package com.tontiflow.infrastructure.client;

import java.math.BigDecimal;

/**
 * Corps de la requête interne de versement (décision R6, symétrique à
 * {@link RecordContributionPayload} — décision R3). Propre à
 * {@code tontine-service}, aucun couplage de compilation avec {@code
 * financial-service}.
 */
record RecordDisbursementPayload(
        Long tontineId,
        Long roundId,
        Long beneficiaryId,
        BigDecimal amount,
        String currency
) {
}
