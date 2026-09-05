package com.tontiflow.domain.service;

import com.tontiflow.domain.enums.NonCompliantBehavior;
import com.tontiflow.domain.model.TontineConfig;
import com.tontiflow.domain.model.TontineMember;
import com.tontiflow.domain.model.TontineRound;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class EligibilityEngine {

    public boolean isEligible(TontineMember member, TontineConfig config, List<TontineRound> currentRotationRounds) {
        if (!member.isActive() || member.isSuspended() || member.isExcluded()) {
            return false;
        }

        boolean hasReceived = currentRotationRounds.stream()
                .anyMatch(r -> member.getId().equals(r.getBeneficiaryId()));
        if (hasReceived) {
            return false;
        }

        if (!member.hasPaidMandatoryContribution()) {
            return config.getNonCompliantBehavior() == NonCompliantBehavior.ALLOW;
        }

        return true;
    }
}