package com.tontiflow.infrastructure.client;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Décision F-8 : le démarrage de tontine-service échoue si le secret de jeton de service est absent ou trop court. */
class ServiceTokenConfigurationTest {

    private final ServiceTokenConfiguration configuration = new ServiceTokenConfiguration();

    @Test
    void shortOrMissingSecret_failsFast_withoutEchoingIt() {
        String shortSecret = "trop-court";

        assertThatThrownBy(() -> configuration.serviceTokenCodec(shortSecret))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(shortSecret);
        assertThatThrownBy(() -> configuration.serviceTokenCodec(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sufficientlyLongSecret_isAccepted() {
        assertThat(configuration.serviceTokenCodec("0123456789abcdef0123456789abcdef")).isNotNull();
    }
}
