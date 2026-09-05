package com.tontiflow.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * Écriture comptable (décision R2) : regroupe un ensemble équilibré de
 * {@link LedgerLine} (total débit = total crédit, invariant garanti par
 * {@code LedgerService}, jamais par cette classe seule).
 *
 * <p><b>Immuable une fois persistée</b> (décision R2, §9) : aucune méthode
 * de modification n'est appelée après la création initiale nulle part dans
 * le code de {@code financial-service}, et aucun endpoint HTTP
 * (volontairement absent, §20) ne permet de la modifier ou de la
 * supprimer — l'absence de chemin d'accès exposé constitue le garde-fou
 * principal, en complément de la discipline de code. Une correction
 * ultérieure doit toujours passer par une nouvelle {@code JournalEntry}
 * d'écriture inverse, jamais par une modification de celle-ci.</p>
 *
 * <p>{@link #idempotencyKey} porte une contrainte {@code UNIQUE} en base
 * (décision R2, §12) : c'est elle, et non une simple vérification Java, qui
 * garantit qu'une même opération métier ne peut jamais être comptabilisée
 * deux fois, y compris sous concurrence réelle.</p>
 */
@Entity
@Table(name = "journal_entry",
        uniqueConstraints = @UniqueConstraint(name = "uk_journal_entry_idempotency_key", columnNames = "idempotency_key"))
public class JournalEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Identifie l'opération métier à l'origine de l'écriture (ex. {@code "contribution:<tontineId>:<roundId>:<memberId>"}). */
    @Column(name = "business_reference", nullable = false, length = 255)
    private String businessReference;

    /** Nature de l'événement métier (ex. {@code "CONTRIBUTION"}) — chaîne libre documentée, pas un enum : la liste des événements est amenée à grandir, aucune valeur fermée n'est imposée en base. */
    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    /** Clé d'idempotence — garantie d'unicité portée par la contrainte {@code UNIQUE} de la table, pas seulement par ce champ. */
    @Column(name = "idempotency_key", nullable = false, length = 255)
    private String idempotencyKey;

    @Column(nullable = false, length = 500)
    private String description;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public JournalEntry() {
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getBusinessReference() {
        return businessReference;
    }

    public void setBusinessReference(String businessReference) {
        this.businessReference = businessReference;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
