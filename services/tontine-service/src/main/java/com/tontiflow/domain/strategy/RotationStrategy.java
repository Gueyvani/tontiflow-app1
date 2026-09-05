package com.tontiflow.domain.strategy;

import com.tontiflow.domain.enums.RotationType;
import com.tontiflow.domain.model.TontineMember;
import com.tontiflow.domain.model.TontineRound;

import java.util.List;
import java.util.Optional;

public interface RotationStrategy {
    RotationType getType();

    Optional<TontineMember> selectNextBeneficiary(
            List<TontineMember> eligibleMembers,
            List<TontineRound> currentRotationRounds
    );
}