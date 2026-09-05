package com.tontiflow.domain.strategy;

import com.tontiflow.domain.enums.RotationType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class RotationStrategyRegistry {

    private final Map<RotationType, RotationStrategy> strategies;

    public RotationStrategyRegistry(List<RotationStrategy> strategyList) {
        this.strategies = strategyList.stream()
                .collect(Collectors.toMap(RotationStrategy::getType, Function.identity()));
    }

    public RotationStrategy getStrategy(RotationType type) {
        RotationStrategy strategy = strategies.get(type);
        if (strategy == null) {
            return strategies.get(RotationType.SEQUENTIAL); // Stratégie par défaut TontiFlow
        }
        return strategy;
    }
}