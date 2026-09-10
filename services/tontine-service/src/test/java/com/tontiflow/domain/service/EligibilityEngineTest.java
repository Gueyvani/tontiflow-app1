package com.tontiflow.domain.service;

import com.tontiflow.domain.enums.MemberStatus;
import com.tontiflow.domain.enums.NonCompliantBehavior;
import com.tontiflow.domain.model.TontineConfig;
import com.tontiflow.domain.model.TontineMember;
import com.tontiflow.domain.model.TontineRound;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Teste directement le vrai {@link EligibilityEngine} (aucun mock) : les
 * scénarios ci-dessous reflètent exactement les règles présentes dans le
 * code actuel, sans en inventer de nouvelles. En particulier,
 * {@link NonCompliantBehavior#POSTPONE}, {@link NonCompliantBehavior#SUSPEND}
 * et {@link NonCompliantBehavior#TEMPORARY_REPLACEMENT} sont traitées de
 * façon strictement identique par le moteur actuel (rejet), ce que ces
 * tests figent tel quel plutôt que de supposer une distinction inexistante.
 */
class EligibilityEngineTest {

    private final EligibilityEngine engine = new EligibilityEngine();

    @Test
    void inactiveMember_isNotEligible() {
        TontineMember member = compliantMember(1L);
        member.setActive(false);

        assertThat(engine.isEligible(member, compliantConfig(), List.of())).isFalse();
    }

    @Test
    void pendingMember_isNotEligible() {
        // Décision R18 D5 : un membre non lié à un compte TontiFlow (PENDING)
        // ne peut jamais être bénéficiaire.
        TontineMember member = compliantMember(1L);
        member.setStatus(MemberStatus.PENDING);

        assertThat(engine.isEligible(member, compliantConfig(), List.of())).isFalse();
    }

    @Test
    void suspendedMember_isNotEligible() {
        TontineMember member = compliantMember(1L);
        member.setSuspended(true);

        assertThat(engine.isEligible(member, compliantConfig(), List.of())).isFalse();
    }

    @Test
    void excludedMember_isNotEligible() {
        TontineMember member = compliantMember(1L);
        member.setExcluded(true);

        assertThat(engine.isEligible(member, compliantConfig(), List.of())).isFalse();
    }

    @Test
    void memberAlreadyBeneficiaryInCurrentRotation_isNotEligible() {
        TontineMember member = compliantMember(1L);
        TontineRound alreadyServed = new TontineRound();
        alreadyServed.setBeneficiaryId(1L);

        assertThat(engine.isEligible(member, compliantConfig(), List.of(alreadyServed))).isFalse();
    }

    @Test
    void compliantMember_isEligible() {
        TontineMember member = compliantMember(1L);

        assertThat(engine.isEligible(member, compliantConfig(), List.of())).isTrue();
    }

    @Test
    void unpaidContribution_withAllowBehavior_isEligible() {
        TontineMember member = compliantMember(1L);
        member.setPaidMandatoryContribution(false);
        TontineConfig config = configWith(NonCompliantBehavior.ALLOW);

        assertThat(engine.isEligible(member, config, List.of())).isTrue();
    }

    @Test
    void unpaidContribution_withPostponeBehavior_isNotEligible() {
        TontineMember member = compliantMember(1L);
        member.setPaidMandatoryContribution(false);
        TontineConfig config = configWith(NonCompliantBehavior.POSTPONE);

        assertThat(engine.isEligible(member, config, List.of())).isFalse();
    }

    @Test
    void unpaidContribution_withSuspendBehavior_isNotEligible() {
        TontineMember member = compliantMember(1L);
        member.setPaidMandatoryContribution(false);
        TontineConfig config = configWith(NonCompliantBehavior.SUSPEND);

        assertThat(engine.isEligible(member, config, List.of())).isFalse();
    }

    @Test
    void unpaidContribution_withTemporaryReplacementBehavior_isNotEligible() {
        TontineMember member = compliantMember(1L);
        member.setPaidMandatoryContribution(false);
        TontineConfig config = configWith(NonCompliantBehavior.TEMPORARY_REPLACEMENT);

        assertThat(engine.isEligible(member, config, List.of())).isFalse();
    }

    private static TontineMember compliantMember(Long id) {
        TontineMember member = new TontineMember();
        member.setId(id);
        member.setStatus(MemberStatus.ACTIVE);
        member.setActive(true);
        member.setSuspended(false);
        member.setExcluded(false);
        member.setPaidMandatoryContribution(true);
        return member;
    }

    private static TontineConfig compliantConfig() {
        return configWith(NonCompliantBehavior.ALLOW);
    }

    private static TontineConfig configWith(NonCompliantBehavior behavior) {
        TontineConfig config = new TontineConfig();
        config.setNonCompliantBehavior(behavior);
        return config;
    }
}
