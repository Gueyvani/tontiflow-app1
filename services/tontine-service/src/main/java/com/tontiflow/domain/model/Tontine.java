package com.tontiflow.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Agrégat racine du domaine tontine.
 *
 * <p>Jusqu'ici absent : {@code TontineConfig}/{@code TontineMember}/
 * {@code TontineRound} référençaient un {@code tontineId} sans aucune table
 * propriétaire ni contrainte. Champs volontairement minimaux (décision
 * explicite) : aucun statut de cycle de vie n'est modélisé, ce concept
 * n'existant pas ailleurs dans le domaine actuel.</p>
 *
 * <p>{@code creatorUserId} est un {@link UUID}, cohérent avec {@code sub}
 * du JWT — à la différence de {@link TontineMember#getUserId()} (existant,
 * {@code Long}), dette déjà documentée séparément, non traitée ici.</p>
 */
@Entity
@Table(name = "tontine")
public class Tontine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private String name;
    @Column(name = "creator_user_id", nullable = false)
    private UUID creatorUserId;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public Tontine() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public UUID getCreatorUserId() { return creatorUserId; }
    public void setCreatorUserId(UUID creatorUserId) { this.creatorUserId = creatorUserId; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
