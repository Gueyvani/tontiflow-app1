package com.tontiflow.infrastructure.security.jwt;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Duration;

/**
 * Configuration de test fournissant une paire de clés RSA générée en
 * mémoire — jamais écrite sur disque, jamais committée — afin de faire
 * démarrer le contexte Spring complet sans dépendre d'un matériel
 * cryptographique réel ni d'aucune variable d'environnement.
 *
 * <p>Une {@code @TestConfiguration} n'est jamais détectée automatiquement
 * par le scan de composants Spring Boot (par conception) : elle doit être
 * importée explicitement via {@code @Import(JwtTestSecurityConfiguration.class)}
 * par les classes de test qui en ont besoin.</p>
 */
@TestConfiguration
public class JwtTestSecurityConfiguration {

    /**
     * Génère une paire de clés RSA 2048 bits éphémère, propre à l'exécution
     * du test courant.
     *
     * @return une paire de clés RSA jetable
     * @throws Exception si l'algorithme RSA n'est pas disponible (ne devrait jamais arriver)
     */
    @Bean
    public KeyPair jwtTestKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    @Bean
    public PrivateKey jwtSigningKey(KeyPair jwtTestKeyPair) {
        return jwtTestKeyPair.getPrivate();
    }

    @Bean
    public PublicKey jwtVerificationKey(KeyPair jwtTestKeyPair) {
        return jwtTestKeyPair.getPublic();
    }

    @Bean
    public Clock jwtClock() {
        return Clock.systemUTC();
    }

    @Bean
    public JwtProperties jwtProperties() {
        // Ressources factices, vides : non utilisees par AccessTokenService (qui recoit
        // deja les cles resolues ci-dessus). Presentes uniquement pour satisfaire
        // l'invariant de non-nullite du record JwtProperties.
        Resource unused = new ByteArrayResource(new byte[0]);
        return new JwtProperties(unused, unused, "authentication-service-test", Duration.ofMinutes(15));
    }

    @Bean
    public AccessTokenService accessTokenService(PrivateKey jwtSigningKey, PublicKey jwtVerificationKey,
                                                  JwtProperties jwtProperties, Clock jwtClock) {
        return new AccessTokenService(jwtSigningKey, jwtVerificationKey, jwtProperties, jwtClock);
    }
}
