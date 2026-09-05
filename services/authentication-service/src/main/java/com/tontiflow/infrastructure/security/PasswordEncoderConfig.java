package com.tontiflow.infrastructure.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Expose le {@link PasswordEncoder} utilisé pour le hachage des mots de
 * passe de {@code authentication-service}.
 *
 * <p>Utilise {@link BCryptPasswordEncoder}, déjà fourni par
 * {@code spring-boot-starter-security} (présent via {@code security-common})
 * — aucune nouvelle dépendance introduite. Le facteur de coût est
 * configurable (propriété {@code security.password.bcrypt-strength}) avec
 * une valeur par défaut raisonnable (12).</p>
 */
@Configuration
public class PasswordEncoderConfig {

    /**
     * @param strength facteur de coût BCrypt (nombre de tours = 2^strength),
     *                 configurable via {@code security.password.bcrypt-strength}
     * @return l'encodeur de mots de passe du module
     */
    @Bean
    public PasswordEncoder passwordEncoder(
            @Value("${security.password.bcrypt-strength:12}") int strength) {
        return new BCryptPasswordEncoder(strength);
    }
}
