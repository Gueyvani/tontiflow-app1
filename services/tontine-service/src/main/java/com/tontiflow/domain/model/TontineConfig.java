package com.tontiflow.domain.model;

import com.tontiflow.domain.enums.ContributionFrequency;
import com.tontiflow.domain.enums.NonCompliantBehavior;
import com.tontiflow.domain.enums.RotationType;
import jakarta.persistence.*;

import java.math.BigDecimal;

@Entity
@Table(name = "tontine_config")
public class TontineConfig {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tontine_id", nullable = false)
    private Long tontineId;
    @Column(name = "rotation_type", nullable = false)
    private RotationType rotationType = RotationType.SEQUENTIAL;
    @Column(name = "non_compliant_behavior", nullable = false)
    private NonCompliantBehavior nonCompliantBehavior = NonCompliantBehavior.POSTPONE;
    @Column(name = "contribution_amount", nullable = false)
    private BigDecimal contributionAmount;
    @Column(name = "contribution_frequency", nullable = false)
    @Enumerated(EnumType.STRING)
    private ContributionFrequency contributionFrequency;
    @Column(name = "max_members", nullable = false)
    private int maxMembers;
    @Column(name = "reorganisation_allowed", nullable = false)
    private boolean reorganisationAllowed = true;

    public TontineConfig() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getTontineId() { return tontineId; }
    public void setTontineId(Long tontineId) { this.tontineId = tontineId; }

    public RotationType getRotationType() { return rotationType; }
    public void setRotationType(RotationType rotationType) { this.rotationType = rotationType; }

    public NonCompliantBehavior getNonCompliantBehavior() { return nonCompliantBehavior; }
    public void setNonCompliantBehavior(NonCompliantBehavior nonCompliantBehavior) {
        this.nonCompliantBehavior = nonCompliantBehavior;
    }

    public BigDecimal getContributionAmount() { return contributionAmount; }
    public void setContributionAmount(BigDecimal contributionAmount) { this.contributionAmount = contributionAmount; }

    public ContributionFrequency getContributionFrequency() { return contributionFrequency; }
    public void setContributionFrequency(ContributionFrequency contributionFrequency) { this.contributionFrequency = contributionFrequency; }

    public int getMaxMembers() { return maxMembers; }
    public void setMaxMembers(int maxMembers) { this.maxMembers = maxMembers; }

    public boolean isReorganisationAllowed() { return reorganisationAllowed; }
    public void setReorganisationAllowed(boolean reorganisationAllowed) { this.reorganisationAllowed = reorganisationAllowed; }
}