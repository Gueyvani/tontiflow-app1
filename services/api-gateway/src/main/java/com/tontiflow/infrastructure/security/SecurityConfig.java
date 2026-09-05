package com.tontiflow.infrastructure.security;

import com.tontiflow.infrastructure.security.jwt.GatewayJwtVerifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint;

/**
 * Chaîne de sécurité réactive (WebFlux) de l'API Gateway.
 *
 * <p>Le Gateway constitue une barrière d'authentification stricte
 * (JWT RS256 valide obligatoire, hors routes publiques d'authentification)
 * mais ne reproduit aucune règle d'autorisation métier (RBAC) : toute
 * route non publique n'exige que {@code authenticated()}, jamais
 * {@code hasAuthority(...)}. L'autorisation fine reste entièrement du
 * ressort de {@code authentication-service}.</p>
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    /**
     * Résout la clé publique RSA de vérification JWT depuis
     * {@code jwt.public-key-location}. Absent en profil {@code test}, où
     * {@code GatewayJwtTestSecurityConfiguration} fournit une paire de clés
     * éphémère à la place — évite tout conflit de définition de bean.
     */
    @Bean
    @Profile("!test")
    public GatewayJwtVerifier gatewayJwtVerifier(@Value("${jwt.public-key-location}") Resource publicKeyLocation) {
        return GatewayJwtVerifier.fromPublicKeyResource(publicKeyLocation);
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, GatewayJwtVerifier gatewayJwtVerifier) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(exchange -> exchange
                        .pathMatchers("/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh")
                        .permitAll()
                        // /actuator/health public (decision R11, corrections techniques) : une
                        // sonde d'orchestration (Docker/K8s) ne peut pas fournir de JWT. Perimetre
                        // strictement limite a ce seul endpoint - management.endpoints.web.exposure
                        // (application.yml) n'expose que "health" et masque tout detail
                        // (show-details: never). Ne route jamais vers un service en aval : aucune
                        // route Gateway ne matche /actuator/**, resolu localement.
                        .pathMatchers("/actuator/health").permitAll()
                        .anyExchange().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterAt(new GatewayJwtAuthenticationWebFilter(gatewayJwtVerifier), SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }
}
