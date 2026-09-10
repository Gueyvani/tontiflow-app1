package com.tontiflow.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Invitation permettant à une personne de lier un membre {@code PENDING} à
 * son compte TontiFlow (consommation effective en R20-C).
 *
 * <p>Principe repris de {@code RefreshToken} (authentication-service) sans le
 * copier : seul {@link #codeHash} (SHA-256 hex du code brut) est persisté —
 * le code en clair n'est jamais stocké, jamais loggé, jamais réexposé après
 * sa génération. Aucune relation JPA vers {@link TontineMember} :
 * {@link #tontineMemberId} est une simple colonne {@code Long} (l'intégrité
 * référentielle est assurée par une contrainte FK côté base, pas par un
 * mapping objet — évite un couplage de chargement inutile).</p>
 *
 * <p>Cycle de vie : {@link #consumedAt} {@code == null} ⇒ invitation active
 * (utilisable jusqu'à {@link #expiresAt}). {@link #consumedAt} {@code != null}
 * ⇒ invitation inutilisable, soit qu'elle ait été consommée par un claim
 * (R20-C), soit qu'elle ait été invalidée par la génération d'une invitation
 * plus récente pour le même membre (règle : une seule invitation active par
 * membre, garantie par l'index partiel {@code uk_member_invitation_active}).
 * L'historique n'est jamais supprimé.</p>
 */
@Entity
@Table(name = "member_invitation")
public class MemberInvitation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tontine_member_id", nullable = false)
    private Long tontineMemberId;

    /** SHA-256 du code brut, encodé en hexadécimal (64 caractères) — jamais le code en clair. */
    @Column(name = "code_hash", nullable = false, unique = true, length = 64)
    private String codeHash;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /** {@code null} tant que l'invitation est active ; horodatage de mise hors service sinon. */
    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;

    /**
     * Code brut, jamais persisté ({@code @Transient}). Ne transporte la
     * valeur en clair que de sa génération jusqu'à la réponse HTTP renvoyée
     * une seule fois au créateur.
     */
    @Transient
    private String rawCode;

    public MemberInvitation() {
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public Long getTontineMemberId() { return tontineMemberId; }
    public void setTontineMemberId(Long tontineMemberId) { this.tontineMemberId = tontineMemberId; }

    public String getCodeHash() { return codeHash; }
    public void setCodeHash(String codeHash) { this.codeHash = codeHash; }

    public LocalDateTime getIssuedAt() { return issuedAt; }
    public void setIssuedAt(LocalDateTime issuedAt) { this.issuedAt = issuedAt; }

    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }

    public LocalDateTime getConsumedAt() { return consumedAt; }
    public void setConsumedAt(LocalDateTime consumedAt) { this.consumedAt = consumedAt; }

    public String getRawCode() { return rawCode; }
    public void setRawCode(String rawCode) { this.rawCode = rawCode; }
}
