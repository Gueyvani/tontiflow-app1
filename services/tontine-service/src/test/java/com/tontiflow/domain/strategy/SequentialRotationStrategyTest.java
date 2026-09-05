package com.tontiflow.domain.strategy;

import com.tontiflow.domain.model.TontineMember;
import com.tontiflow.domain.model.TontineRound;
import com.tontiflow.domain.strategy.impl.SequentialRotationStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SequentialRotationStrategyTest {

    private SequentialRotationStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new SequentialRotationStrategy();
    }

    @Test
    @DisplayName("Should select member according to sequential order")
    void testSequentialSelection() {
        TontineMember m1 = createMember(1L, 1);
        TontineMember m2 = createMember(2L, 2);
        List<TontineMember> members = List.of(m1, m2);

        Optional<TontineMember> selected = strategy.selectNextBeneficiary(members, List.of());

        assertTrue(selected.isPresent());
        assertEquals(1L, selected.get().getId());
    }

    @Test
    @DisplayName("Should select next member when first member already received pot")
    void testNextMemberSelection() {
        TontineMember m1 = createMember(1L, 1);
        TontineMember m2 = createMember(2L, 2);

        TontineRound round1 = new TontineRound();
        round1.setBeneficiaryId(1L);

        Optional<TontineMember> selected = strategy.selectNextBeneficiary(List.of(m1, m2), List.of(round1));

        assertTrue(selected.isPresent());
        assertEquals(2L, selected.get().getId());
    }

    @Test
    @DisplayName("Should return empty optional when rotation is completed")
    void testRotationEnd() {
        TontineMember m1 = createMember(1L, 1);
        TontineRound round1 = new TontineRound();
        round1.setBeneficiaryId(1L);

        Optional<TontineMember> selected = strategy.selectNextBeneficiary(List.of(m1), List.of(round1));

        assertTrue(selected.isEmpty());
    }

    private TontineMember createMember(Long id, int order) {
        TontineMember m = new TontineMember();
        m.setId(id);
        m.setActive(true);
        m.setSequentialOrder(order);
        return m;
    }
}
