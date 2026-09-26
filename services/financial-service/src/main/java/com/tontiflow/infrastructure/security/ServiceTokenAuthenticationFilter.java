package com.tontiflow.infrastructure.security;

import com.tontiflow.security.jwt.BearerTokenExtractor;
import com.tontiflow.security.jwt.ServiceTokenCodec;
import com.tontiflow.security.jwt.ServiceTokenCodec.ServiceTokenClaims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Authentifie les appels {@code /internal/**} par <b>jeton de service</b>
 * (décision F-8, {@link ServiceTokenCodec}) : jamais par un JWT utilisateur,
 * qui n'est plus accepté sur ces endpoints (il échoue à la vérification
 * HS256 et l'appel reste non authentifié).
 *
 * <p>Chaque portée du jeton devient une autorité {@code SCOPE_<portée>} ;
 * {@code SecurityConfig} décide ensuite quelle portée chaque endpoint exige.
 * Le filtre n'écrit aucune réponse HTTP : sans authentification, c'est le
 * point d'entrée de la chaîne de sécurité qui répond 401.</p>
 *
 * <p>Tout rejet est journalisé en WARN ({@code service_token_rejected}) avec
 * uniquement la catégorie technique, la méthode et le chemin — jamais le
 * jeton, sa signature, ses claims, ni le secret.</p>
 */
public class ServiceTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ServiceTokenAuthenticationFilter.class);
    private static final BearerTokenExtractor BEARER_TOKEN_EXTRACTOR = new BearerTokenExtractor();
    private static final String INTERNAL_PREFIX = "/internal/";

    private final ServiceTokenCodec codec;

    public ServiceTokenAuthenticationFilter(ServiceTokenCodec codec) {
        this.codec = codec;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !request.getRequestURI().startsWith(INTERNAL_PREFIX);
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {
        Optional<String> token = BEARER_TOKEN_EXTRACTOR.extract(request.getHeader("Authorization"));
        if (token.isEmpty()) {
            logRejection("MISSING", request);
        } else {
            try {
                ServiceTokenClaims claims = codec.verify(
                        token.get(), ServiceTokenCodec.SERVICE_TONTINE, ServiceTokenCodec.SERVICE_FINANCIAL);
                SecurityContextHolder.getContext().setAuthentication(toAuthentication(claims));
            } catch (JwtException | IllegalArgumentException e) {
                SecurityContextHolder.clearContext();
                logRejection(e.getClass().getSimpleName(), request);
            }
        }
        filterChain.doFilter(request, response);
    }

    private static UsernamePasswordAuthenticationToken toAuthentication(ServiceTokenClaims claims) {
        List<GrantedAuthority> authorities = claims.scopes().stream()
                .map(scope -> "SCOPE_" + scope)
                .map(SimpleGrantedAuthority::new)
                .map(GrantedAuthority.class::cast)
                .toList();
        // Principal = identite du service appelant. on_behalf_of (audit) n'est jamais
        // utilise pour autoriser : l'autorisation metier est celle de l'appelant.
        return new UsernamePasswordAuthenticationToken(claims.subject(), null, authorities);
    }

    private static void logRejection(String reason, HttpServletRequest request) {
        log.warn("service_token_rejected : reason={} method={} path={}",
                reason, request.getMethod(), request.getRequestURI());
    }
}
