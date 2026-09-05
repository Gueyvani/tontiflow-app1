package com.tontiflow.domain.strategy.impl;

import com.tontiflow.domain.model.TontineMember;
import com.tontiflow.domain.model.TontineRound;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RandomRotationStrategy} utilise {@code Collections.shuffle} sans
 * source de hasard injectable : ces tests vérifient donc les invariants
 * garantis par le code (appartenance à l'ensemble éligible, exclusion des
 * déjà-servis), jamais une identité exacte du membre sélectionné.
 */
class RandomRotationStrategyTest {

    private final RandomRotationStrategy strategy = new RandomRotationStrategy();

    @Test
    void emptyEligibleList_returnsEmpty() {
        Optional<TontineMember> result = strategy.selectNextBeneficiary(List.of(), List.of());

        assertThat(result).isEmpty();
    }

    @Test
    void singleCandidate_isReturned() {
        TontineMember onlyCandidate = member(1L);

        Optional<TontineMember> result = strategy.selectNextBeneficiary(List.of(onlyCandidate), List.of());

        assertThat(result).contains(onlyCandidate);
    }

    @Test
    void multipleCandidates_resultAlwaysBelongsToEligibleSet() {
        List<TontineMember> members = List.of(member(1L), member(2L), member(3L));

        Optional<TontineMember> result = strategy.selectNextBeneficiary(members, List.of());

        assertThat(result).isPresent();
        assertThat(members).contains(result.get());
    }

    @Test
    void memberAlreadyServed_isNeverSelectedAgain() {
        TontineMember alreadyServed = member(1L);
        TontineMember stillEligible = member(2L);
        TontineRound previousRound = new TontineRound();
        previousRound.setBeneficiaryId(alreadyServed.getId());

        Optional<TontineMember> result = strategy.selectNextBeneficiary(
                List.of(alreadyServed, stillEligible), List.of(previousRound));

        assertThat(result).contains(stillEligible);
    }

    @Test
    void allMembersAlreadyServed_returnsEmpty() {
        TontineMember member1 = member(1L);
        TontineMember member2 = member(2L);
        TontineRound round1 = new TontineRound();
        round1.setBeneficiaryId(1L);
        TontineRound round2 = new TontineRound();
        round2.setBeneficiaryId(2L);

        Optional<TontineMember> result = strategy.selectNextBeneficiary(
                List.of(member1, member2), List.of(round1, round2));

        assertThat(result).isEmpty();
    }

    @Test
    void roundsWithoutBeneficiaryId_leaveAllMembersAsCandidates() {
        List<TontineMember> members = List.of(member(1L), member(2L));
        TontineRound plannedRound = new TontineRound();
        // beneficiaryId volontairement non défini (round encore PLANNED)

        Optional<TontineMember> result = strategy.selectNextBeneficiary(members, List.of(plannedRound));

        assertThat(result).isPresent();
        assertThat(members).contains(result.get());
    }

    private static TontineMember member(Long id) {
        TontineMember member = new TontineMember();
        member.setId(id);
        return member;
    }
}
