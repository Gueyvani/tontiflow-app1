package com.tontiflow.infrastructure.repository;

import com.tontiflow.domain.model.TontineMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TontineMemberRepository extends JpaRepository<TontineMember, Long> {

    List<TontineMember> findByTontineId(Long tontineId);

    Optional<TontineMember> findByTontineIdAndUserId(Long tontineId, Long userId);
}