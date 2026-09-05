package com.tontiflow.infrastructure.repository;

import com.tontiflow.domain.model.TontineConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TontineConfigRepository extends JpaRepository<TontineConfig, Long> {

    Optional<TontineConfig> findByTontineId(Long tontineId);
}