package com.tontiflow.discovery_service.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Securite HTTP Basic service-to-service du serveur Eureka.
 *
 * <p>Ce n'est pas le patron JWT utilisateur (JwtVerifier / UserContext) utilise
 * par les microservices metier : {@code discovery-service} n'authentifie pas
 * des utilisateurs finaux mais des clients Eureka (autres services du
 * monorepo). L'identifiant/mot de passe partages sont ceux de
 * {@code spring.security.user.*} (voir application.yml), externalises via
 * {@code EUREKA_USERNAME}/{@code EUREKA_PASSWORD}.</p>
 *
 * <p>Le CSRF est desactive uniquement pour {@code /eureka/**} : les clients
 * Eureka (enregistrement, heartbeat) n'envoient pas de jeton CSRF, comme
 * documente par Spring Cloud Netflix pour la securisation standard du
 * serveur Eureka. Toute autre route reste protegee par CSRF par defaut.</p>
 */
@Configuration
public class EurekaServerSecurityConfig {

    @Bean
    public SecurityFilterChain eurekaServerFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.ignoringRequestMatchers("/eureka/**"))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults());
        return http.build();
    }
}
