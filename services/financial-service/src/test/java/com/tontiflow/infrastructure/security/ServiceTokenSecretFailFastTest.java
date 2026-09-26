package com.tontiflow.infrastructure.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Décision F-8 : le démarrage de financial-service échoue si le secret de jeton de service est absent ou trop court. */
class ServiceTokenSecretFailFastTest {

    private final SecurityConfig config = new SecurityConfig();

    @Test
    void shortOrMissingSecret_failsFast_withoutEchoingIt() {
        String shortSecret = "trop-court";

        assertThatThrownBy(() -> config.serviceTokenCodec(shortSecret, Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(shortSecret);
        assertThatThrownBy(() -> config.serviceTokenCodec(null, Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sufficientlyLongSecret_isAccepted() {
        assertThat(config.serviceTokenCodec("0123456789abcdef0123456789abcdef", Clock.systemUTC())).isNotNull();
    }
}
