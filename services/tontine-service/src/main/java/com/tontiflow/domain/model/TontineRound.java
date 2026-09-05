package com.tontiflow.domain.model;

import com.tontiflow.domain.enums.RoundStatus;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity // <-- Vérifiez que cette annotation est présente !
@Table(name = "tontine_round",
        uniqueConstraints = @UniqueConstraint(name = "uk_tontine_round", columnNames = {"tontine_id", "round_number"}))
public class TontineRound {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long tontineId;
    private Long beneficiaryId;
    private int roundNumber;
    private BigDecimal amount;
    @Column(name = "start_date", nullable = false)
    private LocalDateTime startDate;
    @Column(name = "end_date", nullable = false)
    private LocalDateTime endDate;
    private RoundStatus status = RoundStatus.PLANNED;

    public TontineRound() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getTontineId() { return tontineId; }
    public void setTontineId(Long tontineId) { this.tontineId = tontineId; }

    public Long getBeneficiaryId() { return beneficiaryId; }
    public void setBeneficiaryId(Long beneficiaryId) { this.beneficiaryId = beneficiaryId; }

    public int getRoundNumber() { return roundNumber; }
    public void setRoundNumber(int roundNumber) { this.roundNumber = roundNumber; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public LocalDateTime getStartDate() { return startDate; }
    public void setStartDate(LocalDateTime startDate) { this.startDate = startDate; }

    public LocalDateTime getEndDate() { return endDate; }
    public void setEndDate(LocalDateTime endDate) { this.endDate = endDate; }

    public RoundStatus getStatus() { return status; }
    public void setStatus(RoundStatus status) { this.status = status; }
}