package com.tontiflow.infrastructure.security;

import com.tontiflow.infrastructure.security.jwt.JwtVerifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.security.KeyPair;
import java.security.KeyPairGenerator;

/**
 * Configuration de test fournissant une paire de clés RSA générée en
 * mémoire — jamais écrite sur disque, jamais committée — afin de faire
 * démarrer le contexte Spring complet de {@code financial-service} sans
 * dépendre d'un matériel cryptographique réel ni d'aucune variable
 * d'environnement.
 *
 * <p>Le bean {@code jwtVerifier} défini ici remplace, sous le profil
 * {@code test}, celui défini par {@link SecurityConfig} (annoté
 * {@code @Profile("!test")}), évitant tout conflit de définition de bean.</p>
 */
@TestConfiguration
public class JwtTestSecurityConfiguration {

    @Bean
    public KeyPair jwtTestKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    @Bean
    public JwtVerifier jwtVerifier(KeyPair jwtTestKeyPair) {
        return new JwtVerifier(jwtTestKeyPair.getPublic());
    }
}
