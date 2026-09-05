package com.tontiflow.domain.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "round_rotation_history")
public class RoundRotationHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long roundId;
    private Long previousBeneficiaryId;
    private Long newBeneficiaryId;
    private String reason;
    private String updatedBy;
    private String modificationType;
    private LocalDateTime timestamp;

    public RoundRotationHistory() {}

    // Getters et Setters

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getRoundId() {
        return roundId;
    }

    public void setRoundId(Long roundId) {
        this.roundId = roundId;
    }

    public Long getPreviousBeneficiaryId() {
        return previousBeneficiaryId;
    }

    public void setPreviousBeneficiaryId(Long previousBeneficiaryId) {
        this.previousBeneficiaryId = previousBeneficiaryId;
    }

    public Long getNewBeneficiaryId() {
        return newBeneficiaryId;
    }

    public void setNewBeneficiaryId(Long newBeneficiaryId) {
        this.newBeneficiaryId = newBeneficiaryId;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }

    public String getModificationType() {
        return modificationType;
    }

    public void setModificationType(String modificationType) {
        this.modificationType = modificationType;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }
}