package com.tontiflow.domain.model;

import com.tontiflow.domain.enums.AccountStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Événement d'audit durable d'une transition administrative du
 * {@link AccountStatus} d'un {@link AuthAccount} (décision R21-RD, D7).
 *
 * <p><b>Append-only</b> : aucun setter n'est exposé — une fois créé, un
 * événement n'est plus jamais modifié par le code applicatif. Cette garantie
 * est renforcée côté persistance par {@code AccountStatusChangeRepository},
 * qui n'expose que {@code save(...)} (insertion) et aucune opération de mise
 * à jour ou de suppression par identifiant.</p>
 *
 * <p>{@code actorAccountId} provient toujours du contexte d'authentification
 * vérifié (JWT déjà validé) — jamais d'une valeur fournie librement par le
 * client dans le corps de la requête (voir
 * {@code RbacController#updateAccountStatus}).</p>
 *
 * <p>{@code reason} n'est jamais exposé dans une réponse HTTP ni journalisé
 * via un log applicatif ordinaire (décision R21-RD, D4/D8) — seule cette
 * table, consultable directement par un administrateur habilité, en porte la
 * trace.</p>
 */
@Entity
@Table(name = "account_status_change")
public class AccountStatusChange {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "actor_account_id", nullable = false)
    private UUID actorAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "old_status", nullable = false, length = 32)
    private AccountStatus oldStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false, length = 32)
    private AccountStatus newStatus;

    /** Longueur maximale alignée sur le seul précédent de champ texte libre de ce service ({@code email VARCHAR(255)}). */
    @Column(nullable = false, length = 255)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Constructeur requis par Hibernate (accès par champ, jamais utilisé directement par le code applicatif). */
    protected AccountStatusChange() {
    }

    public AccountStatusChange(UUID accountId, UUID actorAccountId, AccountStatus oldStatus,
                                AccountStatus newStatus, String reason, Instant createdAt) {
        this.accountId = accountId;
        this.actorAccountId = actorAccountId;
        this.oldStatus = oldStatus;
        this.newStatus = newStatus;
        this.reason = reason;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public UUID getActorAccountId() {
        return actorAccountId;
    }

    public AccountStatus getOldStatus() {
        return oldStatus;
    }

    public AccountStatus getNewStatus() {
        return newStatus;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
