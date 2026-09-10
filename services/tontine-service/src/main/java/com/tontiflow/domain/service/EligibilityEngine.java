package com.tontiflow.domain.service;

import com.tontiflow.domain.enums.MemberStatus;
import com.tontiflow.domain.enums.NonCompliantBehavior;
import com.tontiflow.domain.model.TontineConfig;
import com.tontiflow.domain.model.TontineMember;
import com.tontiflow.domain.model.TontineRound;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class EligibilityEngine {

    public boolean isEligible(TontineMember member, TontineConfig config, List<TontineRound> currentRotationRounds) {
        // Décision R18 D5 : un membre non lié à un compte TontiFlow (PENDING)
        // ne peut pas être bénéficiaire d'un round. Filtré ici en amont de
        // toute stratégie de rotation — couvre l'attribution manuelle
        // (assignNextRoundBeneficiary) comme le SuspendedRoundRetryScheduler.
        if (member.getStatus() != MemberStatus.ACTIVE) {
            return false;
        }

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