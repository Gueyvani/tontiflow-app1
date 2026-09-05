package com.tontiflow.infrastructure.security;

import com.tontiflow.infrastructure.security.jwt.AccessTokenService;
import com.tontiflow.infrastructure.security.jwt.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Configuration Spring Security de {@code authentication-service}.
 *
 * <p>Remplace l'auto-configuration Spring Boot par défaut (utilisateur en
 * mémoire, HTTP Basic/form-login) par une chaîne de filtres strictement
 * stateless authentifiant via Access Token JWT (RS256), portée par
 * {@link JwtAuthenticationFilter}.</p>
 *
 * <p>Politique par défaut : {@code anyRequest().authenticated()}. Seules
 * {@code /api/v1/auth/register}, {@code /api/v1/auth/login}, {@code
 * /api/v1/auth/refresh}, {@code /api/v1/auth/logout} et {@code
 * /actuator/health} sont publiques : les trois routes {@code auth/*}
 * parce que le Refresh Token brut présenté est lui-même le justificatif
 * de l'opération (aucun Access Token requis, y compris déjà expiré) ;
 * {@code /actuator/health} parce qu'une sonde d'orchestration
 * (Docker/K8s) ne peut fournir aucun JWT (décision R11, corrections
 * techniques — periment la décision précédente de garder ce endpoint
 * protégé). Ce dernier n'expose que le statut global
 * ({@code management.endpoint.health.show-details: never},
 * {@code application.yml}), jamais de détail interne. Toute autre
 * route reste protégée.</p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Déclare la chaîne de filtres HTTP du module.
     *
     * @param http               constructeur de configuration Spring Security
     * @param accessTokenService service de validation des Access Token, utilisé par
     *                           {@link JwtAuthenticationFilter}
     * @return la chaîne de filtres assemblée
     * @throws Exception propagée telle quelle par l'API de configuration Spring Security
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, AccessTokenService accessTokenService)
            throws Exception {
        http
                // API stateless (aucune session, aucun cookie) : le CSRF n'a pas de sens ici.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Sans httpBasic()/formLogin(), Spring Security repondrait 403 par defaut
                // faute de point d'entree explicite : on force 401, coherent avec une API JWT.
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh",
                                "/api/v1/auth/logout")
                        .permitAll()
                        // /error doit rester public : sans cela, le forward interne que Spring Boot
                        // effectue vers /error a chaque response.sendError(...) (ex. 403 d'un
                        // AccessDeniedException) retraverse la chaine de securite en tant que requete
                        // anonyme et ecrase le vrai code HTTP par un 401 via l'entrypoint ci-dessus.
                        .requestMatchers("/error").permitAll()
                        // /actuator/health public (decision R11, corrections techniques) : une
                        // sonde d'orchestration (Docker/K8s) ne peut pas fournir de JWT. Perimetre
                        // strictement limite a ce seul endpoint - management.endpoints.web.exposure
                        // (application.yml) n'expose que "health" et masque tout detail
                        // (show-details: never).
                        .requestMatchers("/actuator/health").permitAll()
                        // JwtAuthenticationFilter n'ajoute pas le prefixe "ROLE_" attendu par
                        // hasRole(...) : les autorites correspondent exactement aux noms de role
                        // stockes en base (ex. "ROLE_ADMIN"), d'ou l'usage de hasAuthority(...).
                        .requestMatchers("/api/v1/admin/**").hasAuthority("ROLE_ADMIN")
                        .anyRequest().authenticated())
                .addFilterBefore(
                        new JwtAuthenticationFilter(accessTokenService),
                        UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
