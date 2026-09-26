package com.tontiflow.infrastructure.security;

import com.tontiflow.security.jwt.ServiceTokenCodec;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Configuration de test (décision F-8) : fournit un {@link ServiceTokenCodec} à secret de test fixe
 * (le vrai codec de production est en {@code @Profile("!test")}) et des helpers pour émettre, dans
 * les tests, des jetons de service tels que {@code tontine-service} les émet.
 */
@TestConfiguration
public class ServiceTokenTestConfiguration {

    /** Secret de test uniquement (>= 32 octets) : jamais utilisé hors profil {@code test}. */
    public static final String SECRET = "financial-service-test-only-secret-0123456789";

    @Bean
    public ServiceTokenCodec serviceTokenCodec() {
        return new ServiceTokenCodec(SECRET, Clock.systemUTC());
    }

    /** Jeton valide d'écriture ({@code ledger.write}) émis par tontine-service pour financial-service. */
    public static String writeToken(ServiceTokenCodec codec) {
        return codec.issue(ServiceTokenCodec.SERVICE_TONTINE, ServiceTokenCodec.SERVICE_FINANCIAL,
                List.of(ServiceTokenCodec.SCOPE_LEDGER_WRITE), UUID.randomUUID());
    }

    /** Jeton valide de lecture ({@code ledger.read}). */
    public static String readToken(ServiceTokenCodec codec) {
        return codec.issue(ServiceTokenCodec.SERVICE_TONTINE, ServiceTokenCodec.SERVICE_FINANCIAL,
                List.of(ServiceTokenCodec.SCOPE_LEDGER_READ), UUID.randomUUID());
    }
}
