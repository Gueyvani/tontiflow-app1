package com.tontiflow.infrastructure.client;

import com.tontiflow.security.jwt.ServiceTokenCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Codec des jetons de service (décision F-8) utilisé par {@link FinancialServiceClient}. Le secret
 * partagé ({@code internal-service-token.secret}, variable {@code INTERNAL_SERVICE_TOKEN_SECRET}) n'a
 * aucune valeur par défaut : le démarrage échoue s'il est absent ou de moins de 32 octets.
 */
@Configuration
public class ServiceTokenConfiguration {

    @Bean
    public ServiceTokenCodec serviceTokenCodec(@Value("${internal-service-token.secret}") String secret) {
        return new ServiceTokenCodec(secret, Clock.systemUTC());
    }
}
