package com.tontiflow.interfaces.rest.dto;

import com.tontiflow.domain.model.TontineRound;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Représentation HTTP d'un {@link TontineRound}. Ne réexpose jamais
 * l'entité JPA directement.
 */
public record TontineRoundResponse(
        Long id,
        Long tontineId,
        Long beneficiaryId,
        int roundNumber,
        BigDecimal amount,
        LocalDateTime startDate,
        LocalDateTime endDate,
        String status
) {
    public static TontineRoundResponse from(TontineRound round) {
        return new TontineRoundResponse(
                round.getId(),
                round.getTontineId(),
                round.getBeneficiaryId(),
                round.getRoundNumber(),
                round.getAmount(),
                round.getStartDate(),
                round.getEndDate(),
                round.getStatus().name()
        );
    }
}
