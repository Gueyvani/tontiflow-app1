package com.tontiflow.interfaces.rest.dto;

import com.tontiflow.domain.model.UserProfile;

import java.util.UUID;

public record UserProfileResponse(
        UUID userId,
        String fullName,
        String phoneNumber
) {
    public static UserProfileResponse from(UserProfile profile) {
        return new UserProfileResponse(profile.getId(), profile.getFullName(), profile.getPhoneNumber());
    }
}
