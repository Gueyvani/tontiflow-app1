package com.tontiflow.infrastructure.security;

import com.tontiflow.infrastructure.security.jwt.JwtVerifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Configuration Spring Security de {@code credit-service} — socle
 * d'authentification JWT local, calqué sur le patron déjà validé de
 * {@code user-service}, {@code tontine-service} et {@code financial-service}.
 *
 * <p>Remplace l'auto-configuration Spring Boot par défaut par une chaîne de
 * filtres strictement stateless authentifiant via Access Token JWT (RS256),
 * en parité exacte avec le contrat déjà validé côté
 * {@code authentication-service} et {@code api-gateway}. Aucune règle RBAC
 * métier n'est introduite ici : {@code credit-service} ne possède encore
 * aucun contrôleur métier.</p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Résout la clé publique RSA de vérification JWT depuis
     * {@code jwt.public-key-location}. Absent en profil {@code test}, où
     * {@code JwtTestSecurityConfiguration} fournit une paire de clés
     * éphémère à la place — évite tout conflit de définition de bean.
     */
    @Bean
    @Profile("!test")
    public JwtVerifier jwtVerifier(@Value("${jwt.public-key-location}") Resource publicKeyLocation) {
        return JwtVerifier.fromPublicKeyResource(publicKeyLocation);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtVerifier jwtVerifier) throws Exception {
        http
                // API stateless (aucune session, aucun cookie) : le CSRF n'a pas de sens ici.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Sans httpBasic()/formLogin(), Spring Security repondrait 403 par defaut
                // faute de point d'entree explicite : on force 401, coherent avec une API JWT.
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(authorize -> authorize
                        // /error doit rester public : sans cela, le forward interne que Spring Boot
                        // effectue vers /error a chaque response.sendError(...) retraverse la chaine
                        // de securite en tant que requete anonyme et ecrase le vrai code HTTP par un
                        // 401 via l'entrypoint ci-dessus (meme mecanisme deja identifie et corrige
                        // dans les services deja securises).
                        .requestMatchers("/error").permitAll()
                        // /actuator/health public (decision R11, corrections techniques) : une
                        // sonde d'orchestration (Docker/K8s) ne peut pas fournir de JWT. Perimetre
                        // strictement limite a ce seul endpoint - management.endpoints.web.exposure
                        // (application.yaml) n'expose que "health" et masque tout detail
                        // (show-details: never).
                        .requestMatchers("/actuator/health").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(
                        new JwtAuthenticationFilter(jwtVerifier),
                        UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
