package com.tontiflow.domain.model;

import jakarta.persistence.*;

@Entity
@Table(name = "tontine_member")
public class TontineMember {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long tontineId;
    private Long userId;
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