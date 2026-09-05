package com.tontiflow.infrastructure.security.jwt;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Teste les invariants de configuration valides par le constructeur compact
 * de {@link JwtProperties} : emetteur non vide, duree de vie strictement
 * positive. Le chemin nominal est deja exerce indirectement par
 * {@link AccessTokenServiceTest} et {@link JwtTestSecurityConfiguration}.
 */
class JwtPropertiesTest {

    @Test
    void constructor_withBlankIssuer_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> new JwtProperties(dummyResource(), dummyResource(), "  ", Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("issuer");
    }

    @Test
    void constructor_withZeroAccessTokenTtl_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> new JwtProperties(dummyResource(), dummyResource(), "authentication-service", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("accessTokenTtl");
    }

    @Test
    void constructor_withNegativeAccessTokenTtl_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> new JwtProperties(dummyResource(), dummyResource(), "authentication-service", Duration.ofMinutes(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("accessTokenTtl");
    }

    private static Resource dummyResource() {
        return new ByteArrayResource(new byte[0]);
    }
}
