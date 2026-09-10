package com.tontiflow.interfaces.rest;

import com.tontiflow.UserContext;
import com.tontiflow.application.service.UserProfileService;
import com.tontiflow.domain.model.UserProfile;
import com.tontiflow.interfaces.rest.dto.UserProfileRequest;
import com.tontiflow.interfaces.rest.dto.UserProfileResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Profil utilisateur minimal ({@code GET/PUT /api/v1/users/me}) — première
 * fonctionnalité métier de {@code user-service} (décision R15/R15-B).
 *
 * <p>L'utilisateur cible est toujours dérivé de l'identité JWT authentifiée
 * ({@code UserContext.userId()}), jamais du corps de la requête ou d'un
 * paramètre de chemin — même convention que {@code TontineController}.</p>
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserProfileController {

    private final UserProfileService userProfileService;

    public UserProfileController(UserProfileService userProfileService) {
        this.userProfileService = userProfileService;
    }

    @GetMapping("/me")
    public ResponseEntity<UserProfileResponse> getMyProfile(Authentication authentication) {
        UserContext caller = (UserContext) authentication.getPrincipal();
        UserProfile profile = userProfileService.getProfile(caller.userId());
        return ResponseEntity.ok(UserProfileResponse.from(profile));
    }

    @PutMapping("/me")
    public ResponseEntity<UserProfileResponse> updateMyProfile(
            @Valid @RequestBody UserProfileRequest request, Authentication authentication) {
        UserContext caller = (UserContext) authentication.getPrincipal();
        UserProfile profile = userProfileService.upsertProfile(
                caller.userId(), request.fullName(), request.phoneNumber());
        return ResponseEntity.ok(UserProfileResponse.from(profile));
    }
}
