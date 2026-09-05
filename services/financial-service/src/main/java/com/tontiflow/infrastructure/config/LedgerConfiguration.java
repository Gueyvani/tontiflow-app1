package com.tontiflow.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Câblage Spring minimal du Ledger (décision R2). Même patron que {@code
 * JwtSecurityConfiguration.jwtClock()} (authentication-service) : une
 * horloge injectable, jamais {@code Instant.now()} appelé directement dans
 * le domaine/service — rend les tests déterministes.
 */
@Configuration
public class LedgerConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
