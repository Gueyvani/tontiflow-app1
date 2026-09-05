package com.tontiflow.interfaces.rest.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

public record AddMemberRequest(
        @NotNull @Positive Long userId,
        @PositiveOrZero int sequentialOrder
) {
}
