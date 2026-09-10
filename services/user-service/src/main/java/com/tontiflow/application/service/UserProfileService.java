package com.tontiflow.application.service;

import com.tontiflow.domain.model.UserProfile;
import com.tontiflow.infrastructure.repository.UserProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Cycle de vie minimal du profil utilisateur ({@code GET/PUT /api/v1/users/me}).
 *
 * <p>Le profil est indexé directement sur l'identité JWT authentifiée
 * ({@code UserContext.userId()}) — jamais fourni par le corps de la
 * requête. {@link #upsertProfile} crée le profil s'il n'existe pas encore
 * (aucun endpoint de création dédié n'est prévu par ce périmètre), ou le
 * remplace intégralement s'il existe déjà (sémantique PUT idempotente).</p>
 */
@Service
public class UserProfileService {

    private final UserProfileRepository userProfileRepository;

    public UserProfileService(UserProfileRepository userProfileRepository) {
        this.userProfileRepository = userProfileRepository;
    }

    @Transactional(readOnly = true)
    public UserProfile getProfile(UUID userId) {
        return userProfileRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Profil introuvable pour cet utilisateur"));
    }

    @Transactional
    public UserProfile upsertProfile(UUID userId, String fullName, String phoneNumber) {
        UserProfile profile = userProfileRepository.findById(userId)
                .orElseGet(() -> new UserProfile(userId, fullName, phoneNumber));
        profile.setFullName(fullName);
        profile.setPhoneNumber(phoneNumber);
        return userProfileRepository.save(profile);
    }
}
