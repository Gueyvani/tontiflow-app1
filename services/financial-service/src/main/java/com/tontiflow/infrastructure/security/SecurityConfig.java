package com.tontiflow.infrastructure.security;

import com.tontiflow.security.jwt.ServiceTokenCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.time.Clock;

/**
 * Configuration Spring Security de {@code financial-service} (décision F-8).
 *
 * <p>{@code financial-service} est un service <b>interne</b> : son seul appelant
 * est {@code tontine-service}, qui s'identifie par un <b>jeton de service</b>
 * HS256 court ({@link ServiceTokenCodec}) et non plus par le JWT de
 * l'utilisateur. Un JWT utilisateur n'est plus accepté sur {@code /internal/**} :
 * il n'existe aucun endpoint utilisateur ici, et l'autorisation métier (créateur,
 * appartenance) reste exécutée par {@code tontine-service} avant tout appel.</p>
 *
 * <p>Portées : {@code ledger.write} pour les écritures (POST), {@code ledger.read}
 * pour les lectures (GET). Toute autre requête est refusée.</p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Secret partagé des jetons de service ({@code internal-service-token.secret}) :
     * démarrage en échec s'il est absent ou trop court. Absent en profil {@code test},
     * où {@code ServiceTokenTestConfiguration} fournit un codec à secret de test.
     */
    @Bean
    @Profile("!test")
    public ServiceTokenCodec serviceTokenCodec(@Value("${internal-service-token.secret}") String secret, Clock clock) {
        return new ServiceTokenCodec(secret, clock);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, ServiceTokenCodec serviceTokenCodec)
            throws Exception {
        http
                // API stateless (aucune session, aucun cookie) : le CSRF n'a pas de sens ici.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Sans httpBasic()/formLogin(), Spring Security repondrait 403 par defaut
                // faute de point d'entree explicite : on force 401, coherent avec une API a jeton.
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(authorize -> authorize
                        // /error doit rester public : sans cela, le forward interne que Spring Boot
                        // effectue vers /error a chaque response.sendError(...) retraverse la chaine
                        // de securite en tant que requete anonyme et ecrase le vrai code HTTP par un
                        // 401 via l'entrypoint ci-dessus (meme mecanisme deja identifie et corrige
                        // dans authentication-service, user-service et tontine-service).
                        .requestMatchers("/error").permitAll()
                        // /actuator/health public (decision R11, corrections techniques) : une
                        // sonde d'orchestration (Docker/K8s) ne peut pas fournir de jeton. Perimetre
                        // strictement limite a ce seul endpoint - management.endpoints.web.exposure
                        // (application.yaml) n'expose que "health" et masque tout detail
                        // (show-details: never).
                        .requestMatchers("/actuator/health").permitAll()
                        // Endpoints internes (decision F-8) : portee exigee par methode.
                        .requestMatchers(HttpMethod.POST, "/internal/contributions", "/internal/disbursements")
                        .hasAuthority("SCOPE_" + ServiceTokenCodec.SCOPE_LEDGER_WRITE)
                        .requestMatchers(HttpMethod.GET, "/internal/accounts/**")
                        .hasAuthority("SCOPE_" + ServiceTokenCodec.SCOPE_LEDGER_READ)
                        // Aucun autre endpoint : tout le reste est refuse.
                        .anyRequest().denyAll())
                .addFilterBefore(
                        new ServiceTokenAuthenticationFilter(serviceTokenCodec),
                        UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
