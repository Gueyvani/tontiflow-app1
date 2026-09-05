package com.tontiflow.domain.strategy;

import com.tontiflow.domain.enums.RotationType;
import com.tontiflow.domain.strategy.impl.RandomRotationStrategy;
import com.tontiflow.domain.strategy.impl.SequentialRotationStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Teste {@link RotationStrategyRegistry} directement (sans contexte Spring),
 * en particulier son repli explicite vers {@link RotationType#SEQUENTIAL}
 * lorsqu'aucune strategie n'est enregistree pour le type demande.
 */
class RotationStrategyRegistryTest {

    @Test
    void getStrategy_withRegisteredType_returnsMatchingStrategy() {
        SequentialRotationStrategy sequential = new SequentialRotationStrategy();
        RandomRotationStrategy random = new RandomRotationStrategy();
        RotationStrategyRegistry registry = new RotationStrategyRegistry(List.of(sequential, random));

        RotationStrategy resolved = registry.getStrategy(RotationType.RANDOM);

        assertThat(resolved).isSameAs(random);
    }

    @Test
    void getStrategy_withUnregisteredType_fallsBackToSequential() {
        SequentialRotationStrategy sequential = new SequentialRotationStrategy();
        RotationStrategyRegistry registry = new RotationStrategyRegistry(List.of(sequential));

        RotationStrategy resolved = registry.getStrategy(RotationType.MANUAL);

        assertThat(resolved).isSameAs(sequential);
    }
}
