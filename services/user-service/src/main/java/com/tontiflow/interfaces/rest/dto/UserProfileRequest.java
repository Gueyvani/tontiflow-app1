package com.tontiflow.interfaces.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UserProfileRequest(
        @NotBlank @Size(max = 255) String fullName,
        @Size(max = 32) String phoneNumber
) {
}
