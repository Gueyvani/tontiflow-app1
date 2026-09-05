package com.tontiflow.domain.model;

import com.tontiflow.domain.enums.Currency;
import com.tontiflow.domain.enums.FinancialAccountStatus;
import com.tontiflow.domain.enums.FinancialAccountType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * Compte financier (décision R2) : {@code financial-service} en est le
 * propriétaire exclusif — source unique de vérité pour le Ledger (décision
 * de Phase R2, §5). {@code tontine-service} ne possède ni ne modifie jamais
 * directement un solde financier.
 *
 * <p>{@link #ownerReference} identifie l'entité propriétaire selon {@link
 * #accountType} : pour {@code TONTINE}, il s'agit du {@code tontineId}
 * (tontine-service) ; pour {@code MEMBER}, il s'agit du {@code
 * TontineMember.id} (identité de l'adhésion, pas l'identité JWT globale —
 * un même utilisateur adhérant à plusieurs tontines possède un compte
 * financier distinct par adhésion). Un seul compte par {@code
 * (ownerReference, accountType)} — contrainte {@code UNIQUE} en base.</p>
 *
 * <p>Le solde n'est jamais stocké ici (pas de champ {@code balance}) :
 * il est systématiquement dérivé des {@link LedgerLine} (décision R2, §10)
 * pour éviter toute source de vérité secondaire divergente.</p>
 */
@Entity
@Table(name = "financial_account",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_financial_account_owner_type",
                columnNames = {"owner_reference", "account_type"}))
public class FinancialAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "owner_reference", nullable = false)
    private Long ownerReference;

    @Column(name = "account_type", nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private FinancialAccountType accountType;

    @Column(nullable = false, length = 8)
    @Enumerated(EnumType.STRING)
    private Currency currency;

    @Column(nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private FinancialAccountStatus status = FinancialAccountStatus.ACTIVE;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public FinancialAccount() {
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Long getOwnerReference() {
        return ownerReference;
    }

    public void setOwnerReference(Long ownerReference) {
        this.ownerReference = ownerReference;
    }

    public FinancialAccountType getAccountType() {
        return accountType;
    }

    public void setAccountType(FinancialAccountType accountType) {
        this.accountType = accountType;
    }

    public Currency getCurrency() {
        return currency;
    }

    public void setCurrency(Currency currency) {
        this.currency = currency;
    }

    public FinancialAccountStatus getStatus() {
        return status;
    }

    public void setStatus(FinancialAccountStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
