package com.tontiflow.infrastructure.repository;

import com.tontiflow.domain.enums.RoundStatus;
import com.tontiflow.domain.model.TontineRound;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface TontineRoundRepository extends JpaRepository<TontineRound, Long> {

    List<TontineRound> findByTontineId(Long tontineId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM TontineRound r WHERE r.id = :id")
    Optional<TontineRound> findByIdForUpdate(@Param("id") Long id);

    /**
     * Rounds dont la date de fin est dépassée pour un statut donné —
     * utilisé par la complétion automatique (décision O2 : bascule vers
     * {@code COMPLETED} pilotée par échéance, via job planifié).
     */
    List<TontineRound> findByStatusAndEndDateBefore(RoundStatus status, LocalDateTime endDate);

    /**
     * Rounds ayant le statut donné — utilisé par {@code
     * SuspendedRoundRetryScheduler} (décision S1b) pour retrouver les rounds
     * {@code SUSPENDED} à réévaluer.
     */
    List<TontineRound> findByStatus(RoundStatus status);

    /**
     * Identifiants distincts des tontines possédant au moins un round du
     * statut donné — utilisé par {@code OrphanedCompletedRoundRetryScheduler}
     * (décision S3b, option S3b-1) pour détecter les tontines dont le
     * dernier round est {@code COMPLETED} sans round suivant.
     */
    @Query("SELECT DISTINCT r.tontineId FROM TontineRound r WHERE r.status = :status")
    List<Long> findDistinctTontineIdsByStatus(@Param("status") RoundStatus status);
}