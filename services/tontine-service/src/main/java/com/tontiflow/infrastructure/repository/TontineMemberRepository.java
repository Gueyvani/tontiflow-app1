package com.tontiflow.infrastructure.repository;

import com.tontiflow.domain.model.TontineMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TontineMemberRepository extends JpaRepository<TontineMember, Long> {

    List<TontineMember> findByTontineId(Long tontineId);

    Optional<TontineMember> findByTontineIdAndUserId(Long tontineId, Long userId);

    /**
     * Pré-check de mono-participation (R20-C) : un compte ne peut détenir
     * qu'une seule participation liée dans une tontine donnée. Le garde-fou
     * final en concurrence reste la contrainte
     * {@code uk_tontine_member_tontine_account} (V8).
     */
    Optional<TontineMember> findByTontineIdAndAccountId(Long tontineId, UUID accountId);
}