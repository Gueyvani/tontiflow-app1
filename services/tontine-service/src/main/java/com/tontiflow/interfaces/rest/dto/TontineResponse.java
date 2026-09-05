package com.tontiflow.interfaces.rest.dto;

import com.tontiflow.domain.model.Tontine;

import java.time.LocalDateTime;
import java.util.UUID;

public record TontineResponse(
        Long id,
        String name,
        UUID creatorUserId,
        LocalDateTime createdAt
) {
    public static TontineResponse from(Tontine tontine) {
        return new TontineResponse(
                tontine.getId(), tontine.getName(), tontine.getCreatorUserId(), tontine.getCreatedAt());
    }
}
