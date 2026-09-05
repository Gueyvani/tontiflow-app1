package com.tontiflow.interfaces.rest.dto;

import com.tontiflow.domain.enums.ContributionFrequency;
import com.tontiflow.domain.enums.NonCompliantBehavior;
import com.tontiflow.domain.enums.RotationType;
import com.tontiflow.domain.model.TontineConfig;

import java.math.BigDecimal;

public record TontineConfigResponse(
        Long id,
        Long tontineId,
        BigDecimal contributionAmount,
        ContributionFrequency contributionFrequency,
        int maxMembers,
        RotationType rotationType,
        NonCompliantBehavior nonCompliantBehavior,
        boolean reorganisationAllowed
) {
    public static TontineConfigResponse from(TontineConfig config) {
        return new TontineConfigResponse(
                config.getId(),
                config.getTontineId(),
                config.getContributionAmount(),
                config.getContributionFrequency(),
                config.getMaxMembers(),
                config.getRotationType(),
                config.getNonCompliantBehavior(),
                config.isReorganisationAllowed());
    }
}
