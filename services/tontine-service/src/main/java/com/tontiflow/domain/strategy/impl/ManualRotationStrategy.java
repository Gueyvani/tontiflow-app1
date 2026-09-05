package com.tontiflow.domain.strategy.impl;

import com.tontiflow.domain.enums.RotationType;
import com.tontiflow.domain.model.TontineMember;
import com.tontiflow.domain.model.TontineRound;
import com.tontiflow.domain.strategy.RotationStrategy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class ManualRotationStrategy implements RotationStrategy {

    @Override
    public RotationType getType() {
        return RotationType.MANUAL;
    }

    @Override
    public Optional<TontineMember> selectNextBeneficiary(
            List<TontineMember> eligibleMembers,
            List<TontineRound> currentRotationRounds) {
        // En mode manuel, la sélection est explicitement passée par l'administrateur
        return Optional.empty();
    }
}