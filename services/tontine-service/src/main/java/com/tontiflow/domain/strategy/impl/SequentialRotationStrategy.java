package com.tontiflow.domain.strategy.impl;

import com.tontiflow.domain.enums.RotationType;
import com.tontiflow.domain.model.TontineMember;
import com.tontiflow.domain.model.TontineRound;
import com.tontiflow.domain.strategy.RotationStrategy;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class SequentialRotationStrategy implements RotationStrategy {

    @Override
    public RotationType getType() {
        return RotationType.SEQUENTIAL;
    }

    @Override
    public Optional<TontineMember> selectNextBeneficiary(
            List<TontineMember> eligibleMembers,
            List<TontineRound> currentRotationRounds) {

        Set<Long> alreadyReceived = currentRotationRounds.stream()
                .filter(r -> r.getBeneficiaryId() != null)
                .map(TontineRound::getBeneficiaryId)
                .collect(Collectors.toSet());

        return eligibleMembers.stream()
                .filter(member -> !alreadyReceived.contains(member.getId()))
                .min(Comparator.comparingInt(TontineMember::getSequentialOrder));
    }
}
