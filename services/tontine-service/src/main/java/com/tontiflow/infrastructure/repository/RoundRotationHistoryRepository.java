package com.tontiflow.infrastructure.repository;

import com.tontiflow.domain.model.RoundRotationHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RoundRotationHistoryRepository extends JpaRepository<RoundRotationHistory, Long> {

    List<RoundRotationHistory> findByRoundId(Long roundId);
}