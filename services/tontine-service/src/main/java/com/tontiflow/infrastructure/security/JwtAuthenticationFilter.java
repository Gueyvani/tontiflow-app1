package com.tontiflow.infrastructure.security;

import com.tontiflow.UserContext;
import com.tontiflow.infrastructure.security.jwt.JwtVerifier;
import com.tontiflow.security.jwt.BearerTokenExtractor;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Filtre HTTP unique authentifiant une requête à partir d'un Access Token
 * JWT porté par l'en-tête {@code Authorization: Bearer <token>}.
 *
 * <p>Réutilise strictement {@link BearerTokenExtractor} (extraction du
 * Bearer) et {@link JwtVerifier} (validation du JWT) — même découpage de
 * responsabilités que le patron déjà validé de {@code user-service} et
 * {@code authentication-service}. Le filtre est {@code OncePerRequestFilter}
 * (exécution unique par requête) et strictement stateless : aucune session
 * n'est créée ni lue.</p>
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final BearerTokenExtractor BEARER_TOKEN_EXTRACTOR = new BearerTokenExtractor();

    private final JwtVerifier jwtVerifier;

    public JwtAuthenticationFilter(JwtVerifier jwtVerifier) {
        this.jwtVerifier = jwtVerifier;
    }

    /**
     * Extrait et valide le Bearer Token s'il est présent, puis peuple le
     * {@link SecurityContextHolder}. En l'absence de token, ou si le token
     * est invalide/expiré, la requête poursuit simplement non authentifiée :
     * c'est aux règles d'autorisation de la chaîne de sécurité de décider
     * du rejet (aucune réponse HTTP n'est écrite directement par ce filtre).
     */
    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {

        Optional<String> token = BEARER_TOKEN_EXTRACTOR.extract(request.getHeader("Authorization"));

        if (token.isPresent()) {
            try {
                UserContext userContext = jwtVerifier.verify(token.get());
                SecurityContextHolder.getContext().setAuthentication(toAuthentication(userContext));
            } catch (JwtException e) {
                // Token present mais invalide/expire/mal signe : on n'authentifie pas la
                // requete (erreur cryptographique jamais masquee, mais pas de reponse
                // HTTP ecrite ici - c'est le role de la chaine de securite).
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }

    private static UsernamePasswordAuthenticationToken toAuthentication(UserContext userContext) {
        List<GrantedAuthority> authorities = Stream.concat(
                        userContext.roles().stream(),
                        userContext.permissions().stream())
                .map(SimpleGrantedAuthority::new)
                .map(GrantedAuthority.class::cast)
                .toList();

        return new UsernamePasswordAuthenticationToken(userContext, null, authorities);
    }
}
