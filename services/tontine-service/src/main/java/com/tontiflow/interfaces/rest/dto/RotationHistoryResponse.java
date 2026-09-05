package com.tontiflow.interfaces.rest.dto;

import com.tontiflow.domain.model.RoundRotationHistory;

import java.time.LocalDateTime;

/**
 * Représentation HTTP d'un {@link RoundRotationHistory} (décision R9). Ne
 * réexpose jamais l'entité JPA directement — même discipline que {@link
 * TontineRoundResponse}.
 */
public record RotationHistoryResponse(
        Long id,
        Long roundId,
        Long previousBeneficiaryId,
        Long newBeneficiaryId,
        String reason,
        String updatedBy,
        String modificationType,
        LocalDateTime timestamp
) {
    public static RotationHistoryResponse from(RoundRotationHistory history) {
        return new RotationHistoryResponse(
                history.getId(),
                history.getRoundId(),
                history.getPreviousBeneficiaryId(),
                history.getNewBeneficiaryId(),
                history.getReason(),
                history.getUpdatedBy(),
                history.getModificationType(),
                history.getTimestamp()
        );
    }
}
