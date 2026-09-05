package com.tontiflow.infrastructure.security.jwt;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Clock;

/**
 * Câblage Spring de la sécurité JWT pour l'exécution réelle du module.
 *
 * <p>Charge la paire de clés RSA configurée dans {@code application.yml}
 * (section {@code jwt:}) via {@link RsaKeyLoader} et expose
 * {@link AccessTokenService} comme bean applicatif.</p>
 *
 * <p>Inactive en profil {@code test} ({@code @Profile("!test")}) : les tests
 * du module démarrent le contexte Spring complet
 * ({@code AuthenticationServiceApplicationTests}) sans qu'aucune clé RSA
 * réelle ne soit configurée. C'est {@code JwtTestSecurityConfiguration}
 * (côté test, clé générée en mémoire) qui fournit les mêmes beans dans ce cas.</p>
 */
@Configuration
@Profile("!test")
@EnableConfigurationProperties(JwtProperties.class)
public class JwtSecurityConfiguration {

    /**
     * Charge la clé privée RSA utilisée pour signer les Access Token émis.
     *
     * @param properties configuration JWT liée à {@code application.yml}
     * @return la clé privée décodée
     */
    @Bean
    public PrivateKey jwtSigningKey(JwtProperties properties) {
        return RsaKeyLoader.loadPrivateKey(properties.privateKeyResource());
    }

    /**
     * Charge la clé publique RSA utilisée pour valider les Access Token reçus.
     *
     * @param properties configuration JWT liée à {@code application.yml}
     * @return la clé publique décodée
     */
    @Bean
    public PublicKey jwtVerificationKey(JwtProperties properties) {
        return RsaKeyLoader.loadPublicKey(properties.publicKeyResource());
    }

    /**
     * Horloge système utilisée pour dater/expirer les tokens en conditions réelles.
     *
     * @return une horloge UTC système
     */
    @Bean
    public Clock jwtClock() {
        return Clock.systemUTC();
    }

    /**
     * Service de signature/validation des Access Token, assemblé à partir
     * des clés et de la configuration ci-dessus.
     *
     * @param jwtSigningKey      clé privée de signature
     * @param jwtVerificationKey clé publique de validation
     * @param properties         configuration (émetteur, durée de vie)
     * @param jwtClock           horloge de datation/expiration
     * @return le service prêt à l'emploi
     */
    @Bean
    public AccessTokenService accessTokenService(PrivateKey jwtSigningKey, PublicKey jwtVerificationKey,
                                                  JwtProperties properties, Clock jwtClock) {
        return new AccessTokenService(jwtSigningKey, jwtVerificationKey, properties, jwtClock);
    }
}
