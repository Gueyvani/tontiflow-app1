package com.tontiflow.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Profil métier minimal d'un utilisateur, distinct des identifiants de
 * connexion portés par {@code AuthAccount} (authentication-service).
 *
 * <p>{@link #id} est l'identifiant utilisateur porté par le claim JWT
 * {@code sub} ({@code UserContext.userId()}, lui-même égal à
 * {@code AuthAccount.id} côté authentication-service) — aucun identifiant
 * distinct n'est généré par {@code user-service}, le profil est indexé
 * directement sur l'identité authentifiée.</p>
 */
@Entity
@Table(name = "user_profile")
public class UserProfile {

    @Id
    private UUID id;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "phone_number")
    private String phoneNumber;

    public UserProfile() {
    }

    public UserProfile(UUID id, String fullName, String phoneNumber) {
        this.id = id;
        this.fullName = fullName;
        this.phoneNumber = phoneNumber;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }
}
