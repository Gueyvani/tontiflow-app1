package com.tontiflow.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import java.time.Instant;
import java.util.UUID;

/**
 * Refresh Token émis à un compte, permettant le renouvellement d'un Access
 * Token sans nouvelle saisie de mot de passe.
 *
 * <p>Seul {@link #tokenHash} (SHA-256 du token brut) est persisté — le token
 * en clair n'est jamais stocké. Aucune relation JPA vers {@code AuthAccount}
 * n'est déclarée : {@link #accountId} est une simple colonne, afin de ne pas
 * modifier l'entité {@code AuthAccount} existante.</p>
 *
 * <p>{@link #familyId} identifie la lignée de rotation : chaque renouvellement
 * émet un nouveau token portant le même {@code familyId} que le précédent,
 * ce qui permet de révoquer toute la famille en cas de détection de
 * réutilisation (vol probable du token).</p>
 */
@Entity
@Table(name = "refresh_token")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    /** SHA-256 du token brut, encodé en hexadécimal (64 caractères) — jamais le token en clair. */
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** {@code null} tant que le token est actif ; horodatage de révocation sinon. */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    /**
     * Valeur brute du token, jamais persistée ({@code @Transient}). Ne sert
     * qu'à transporter le token en mémoire, de sa génération jusqu'à la
     * réponse HTTP renvoyée une seule fois au client.
     */
    @Transient
    private String rawToken;

    public RefreshToken() {
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public void setAccountId(UUID accountId) {
        this.accountId = accountId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public UUID getFamilyId() {
        return familyId;
    }

    public void setFamilyId(UUID familyId) {
        this.familyId = familyId;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public void setIssuedAt(Instant issuedAt) {
        this.issuedAt = issuedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(Instant revokedAt) {
        this.revokedAt = revokedAt;
    }

    public String getRawToken() {
        return rawToken;
    }

    public void setRawToken(String rawToken) {
        this.rawToken = rawToken;
    }
}
