package com.tontiflow.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Securite HTTP Basic service-to-service du Config Server.
 *
 * <p>Ce n'est pas le patron JWT utilisateur (JwtVerifier / UserContext) utilise
 * par les microservices metier : {@code config-service} ne traite aucune
 * requete utilisateur final, uniquement des lectures de configuration par des
 * Config Clients (autres services du monorepo). L'identifiant/mot de passe
 * partages sont ceux de {@code spring.security.user.*} (voir application.yml),
 * externalises via {@code CONFIG_SERVER_USERNAME}/{@code CONFIG_SERVER_PASSWORD}.</p>
 *
 * <p>Le CSRF reste active (comportement par defaut de Spring Security) : les
 * endpoints du Config Server ({@code /{application}/{profile}[...]}) ne sont
 * accessibles qu'en lecture (GET), methode naturellement exemptee de la
 * protection CSRF - aucune desactivation n'est donc necessaire, a la
 * difference d'Eureka qui requiert des appels PUT/POST (enregistrement,
 * heartbeat).</p>
 *
 * <p>Decision explicite : {@code /actuator/health} est le seul endpoint
 * public (sans authentification). Justification - un health check est
 * generalement interroge par une sonde d'infrastructure (orchestrateur,
 * load balancer) qui ne peut pas necessairement fournir de credentials, et
 * ne revele aucune information sensible ici ({@code show-details: never},
 * voir application.yml - reponse limitee a {@code {"status":"UP"}}).
 * {@code /actuator/info} et tout le reste (y compris les endpoints de
 * configuration eux-memes) restent proteges par HTTP Basic.</p>
 */
@Configuration
public class ConfigServerSecurityConfig {

    @Bean
    public SecurityFilterChain configServerFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health").permitAll()
                        .anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults());
        return http.build();
    }
}
