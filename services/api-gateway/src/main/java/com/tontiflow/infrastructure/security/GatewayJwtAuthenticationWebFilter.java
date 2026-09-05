package com.tontiflow.infrastructure.security;

import com.tontiflow.UserContext;
import com.tontiflow.infrastructure.security.jwt.GatewayJwtVerifier;
import com.tontiflow.security.jwt.BearerTokenExtractor;
import io.jsonwebtoken.JwtException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Filtre réactif authentifiant une requête à partir d'un Access Token JWT
 * porté par l'en-tête {@code Authorization: Bearer <token>}.
 *
 * <p>Réutilise strictement {@link BearerTokenExtractor} (extraction du
 * Bearer) et {@link GatewayJwtVerifier} (vérification du JWT) — même
 * découpage de responsabilités que {@code JwtAuthenticationFilter} côté
 * {@code authentication-service}. En cas de token absent, malformé, expiré
 * ou de signature invalide, la requête poursuit simplement non authentifiée :
 * ce filtre n'écrit jamais de réponse HTTP lui-même, c'est à la
 * {@code SecurityWebFilterChain} de décider du rejet (401).</p>
 */
public class GatewayJwtAuthenticationWebFilter implements WebFilter {

    private static final BearerTokenExtractor BEARER_TOKEN_EXTRACTOR = new BearerTokenExtractor();

    private final GatewayJwtVerifier gatewayJwtVerifier;

    public GatewayJwtAuthenticationWebFilter(GatewayJwtVerifier gatewayJwtVerifier) {
        this.gatewayJwtVerifier = gatewayJwtVerifier;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        Optional<String> token = BEARER_TOKEN_EXTRACTOR.extract(
                exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION));

        if (token.isEmpty()) {
            return chain.filter(exchange);
        }

        try {
            UserContext userContext = gatewayJwtVerifier.verify(token.get());
            return chain.filter(exchange)
                    .contextWrite(ReactiveSecurityContextHolder.withAuthentication(toAuthentication(userContext)));
        } catch (JwtException e) {
            // Token present mais invalide/expire/mal signe : on n'authentifie pas la
            // requete (erreur cryptographique jamais masquee en interne, mais aucune
            // reponse HTTP ni detail sensible ecrit ici - role de la chaine de securite).
            return chain.filter(exchange);
        }
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
