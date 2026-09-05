package com.tontiflow.domain.strategy.impl;

import com.tontiflow.domain.model.TontineMember;
import com.tontiflow.domain.model.TontineRound;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Manual rotation is not functionally implemented yet; this test freezes
 * current behavior. {@code selectNextBeneficiary} ignores both parameters
 * and always returns {@link Optional#empty()} — this is not a bug fix or a
 * new feature, it is a regression-coverage test of the code exactly as it
 * exists today. Rendering MANUAL rotation actually functional (accepting a
 * manually chosen beneficiary) would require a dedicated DTO/endpoint and a
 * business decision on who may choose and how — deliberately out of scope
 * here.
 */
class ManualRotationStrategyTest {

    private final ManualRotationStrategy strategy = new ManualRotationStrategy();

    @Test
    void alwaysReturnsEmpty_withEmptyLists() {
        Optional<TontineMember> result = strategy.selectNextBeneficiary(List.of(), List.of());

        assertThat(result).isEmpty();
    }

    @Test
    void alwaysReturnsEmpty_withEligibleCandidatesPresent() {
        TontineMember candidate = new TontineMember();
        candidate.setId(1L);

        Optional<TontineMember> result = strategy.selectNextBeneficiary(List.of(candidate), List.of());

        assertThat(result).isEmpty();
    }

    @Test
    void alwaysReturnsEmpty_regardlessOfPreviousRounds() {
        TontineMember candidate = new TontineMember();
        candidate.setId(1L);
        TontineRound previousRound = new TontineRound();
        previousRound.setBeneficiaryId(2L);

        Optional<TontineMember> result = strategy.selectNextBeneficiary(List.of(candidate), List.of(previousRound));

        assertThat(result).isEmpty();
    }
}
