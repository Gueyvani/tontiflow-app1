package com.tontiflow.domain.model;

import com.tontiflow.domain.enums.MemberStatus;
import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(name = "tontine_member")
public class TontineMember {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long tontineId;

    /**
     * Dette historique (audits R17/R18) : identifiant {@code Long} arbitraire
     * fourni par le créateur, jamais relié à l'identité JWT réelle. Conservé
     * de façon transitoire pour compatibilité API/contrainte
     * {@code uk_tontine_member_tontine_user} ; sa suppression est reportée à
     * une phase ultérieure. Ne pas confondre avec {@link #accountId}.
     */
    private Long userId;

    /**
     * UUID du compte TontiFlow lié à ce membre
     * ({@code = AuthAccount.id = UserProfile.id = claim JWT sub}).
     * {@code null} tant que le membre est {@link MemberStatus#PENDING}.
     *
     * <p>Distinct de {@link #id} : {@code id} est l'identifiant interne de la
     * participation (référencé par {@code TontineRound.beneficiaryId} et par
     * {@code financial_account.owner_reference}), {@code accountId} est
     * l'identité du compte. Les deux ne doivent jamais être confondus.</p>
     *
     * <p>Aucune relation JPA vers {@code user-service} : l'identité est
     * distribuée par UUID (pattern database-per-service). Volontaire.</p>
     */
    @Column(name = "account_id")
    private UUID accountId;

    /**
     * Statut de liaison au compte (décisions R18 D1/D5). Défaut
     * {@link MemberStatus#PENDING} : un membre nouvellement ajouté n'est pas
     * encore lié à un compte authentifiable.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private MemberStatus status = MemberStatus.PENDING;

    private int sequentialOrder;
    private boolean active = true;
    private boolean suspended = false;
    private boolean excluded = false;
    private boolean paidMandatoryContribution = true;

    public TontineMember() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getTontineId() { return tontineId; }
    public void setTontineId(Long tontineId) { this.tontineId = tontineId; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public UUID getAccountId() { return accountId; }
    public void setAccountId(UUID accountId) { this.accountId = accountId; }

    public MemberStatus getStatus() { return status; }
    public void setStatus(MemberStatus status) { this.status = status; }

    public int getSequentialOrder() { return sequentialOrder; }
    public void setSequentialOrder(int sequentialOrder) { this.sequentialOrder = sequentialOrder; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public boolean isSuspended() { return suspended; }
    public void setSuspended(boolean suspended) { this.suspended = suspended; }

    public boolean isExcluded() { return excluded; }
    public void setExcluded(boolean excluded) { this.excluded = excluded; }

    public boolean hasPaidMandatoryContribution() { return paidMandatoryContribution; }
    public void setPaidMandatoryContribution(boolean paidMandatoryContribution) {
        this.paidMandatoryContribution = paidMandatoryContribution;
    }
}
