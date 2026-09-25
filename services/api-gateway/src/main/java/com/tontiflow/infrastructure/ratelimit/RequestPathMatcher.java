package com.tontiflow.infrastructure.ratelimit;

import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.List;

/**
 * Détermine si une requête appartient à un endpoint protégé par un rate limiter,
 * sur la MÊME représentation du chemin que la chaîne Spring Security et les
 * prédicats {@code Path} de Spring Cloud Gateway (TICKET-5, constat F-7).
 *
 * <p>Ces derniers évaluent un {@link PathPattern} sur
 * {@code request.getPath().pathWithinApplication()} : le décodage se fait segment
 * par segment ({@code %74} vaut {@code t}). Les rate limiters comparaient au
 * contraire une regex à {@code getURI().getRawPath()} : {@code /logou%74} était
 * public pour Security et routé vers l'aval, mais échappait au quota. Ce helper
 * supprime cet écart sans aucun décodage manuel : tout le décodage est celui de
 * Spring.</p>
 *
 * <p>Les variantes que le pare-feu de Spring Security rejette avant les filtres
 * ({@code ;x=1}, {@code %2F}, {@code %25}, {@code //}, ...) ne sont volontairement
 * pas traitées ici : ce helper ne reproduit pas le pare-feu.</p>
 *
 * <p>Ne contient ni limitation de débit, ni logique IP, ni réponse 429.</p>
 */
final class RequestPathMatcher {

    private static final PathPatternParser PARSER = PathPatternParser.defaultInstance;

    private final List<PathPattern> patterns;

    private RequestPathMatcher(List<PathPattern> patterns) {
        this.patterns = patterns;
    }

    /**
     * Endpoint exact, avec ou sans slash final ({@code /x} et {@code /x/}) : conserve le
     * contrat historique des regex {@code ^...(/?)$}. Deux motifs explicites plutôt que
     * {@code PathPatternParser#setMatchOptionalTrailingSeparator}, déprécié depuis Spring 6.0.
     */
    static RequestPathMatcher exactWithOptionalTrailingSlash(String pattern) {
        return new RequestPathMatcher(List.of(PARSER.parse(pattern), PARSER.parse(pattern + "/")));
    }

    /** Motif utilisé tel quel (ex. {@code /api/v1/admin/**}, qui couvre déjà la racine et le slash final). */
    static RequestPathMatcher of(String pattern) {
        return new RequestPathMatcher(List.of(PARSER.parse(pattern)));
    }

    boolean matches(ServerHttpRequest request) {
        var path = request.getPath().pathWithinApplication();
        for (PathPattern pattern : patterns) {
            if (pattern.matches(path)) {
                return true;
            }
        }
        return false;
    }
}
