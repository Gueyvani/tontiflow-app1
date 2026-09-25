package com.tontiflow.infrastructure.ratelimit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitaires de {@link RequestPathMatcher} (TICKET-5, constat F-7).
 *
 * <p>Les requetes sont construites avec {@link URI#create(String)} : le helper
 * {@code MockServerHttpRequest.method(method, "<template>")} reencoderait {@code %} en
 * {@code %25} et rendrait les cas encodes vacueux.</p>
 */
class RequestPathMatcherTest {

    private static ServerHttpRequest requestTo(String rawPath) {
        URI uri = URI.create(rawPath);
        assertThat(uri.getRawPath()).isEqualTo(rawPath);
        return MockServerHttpRequest.method(HttpMethod.POST, uri).build();
    }

    private static final RequestPathMatcher LOGOUT =
            RequestPathMatcher.exactWithOptionalTrailingSlash("/api/v1/auth/logout");

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "/api/v1/auth/logout,       true",
            "/api/v1/auth/logout/,      true",
            "/api/v1/auth/logou%74,     true",
            "/api/v1/auth/%6Cogout,     true",
            "/api/v1/auth/logou%74/,    true",
            "/api/v1/auth/%6Cogou%74,   true",
            "/api/v1/auth/logout-all,   false",
            "/api/v1/auth/logout%2Dall, false",
            "/api/v1/auth/logoutx,      false",
            "/api/v1/auth/LOGOUT,       false",
            "/api/v1/auth,              false",
            "/api/v1/auth/logout/x,     false",
            "/x/api/v1/auth/logout,     false"
    })
    void exactEndpoint_isMatchedOnTheDecodedSegments(String rawPath, boolean expected) {
        assertThat(LOGOUT.matches(requestTo(rawPath))).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "/api/v1/admin,             true",
            "/api/v1/admin/,            true",
            "/api/v1/admin/roles,       true",
            "/api/v1/admin/roles/,      true",
            "/api/v1/admin/roles/1/x,   true",
            "/api/v1/adm%69n,           true",
            "/api/v1/adm%69n/roles,     true",
            "/api/v1/%61dmin/%72oles,   true",
            "/api/v1/administration,    false",
            "/api/v1/adm%69nistration,  false",
            "/api/v1/administration/x,  false",
            "/api/v1/Admin,             false",
            "/api/v1/users/admin,       false"
    })
    void adminPrefix_coversRootAndSubPaths_butNotLookAlikes(String rawPath, boolean expected) {
        assertThat(RequestPathMatcher.of("/api/v1/admin/**").matches(requestTo(rawPath))).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "/api/v1/tontines/abc/members/claim,       true",
            "/api/v1/tontines/abc/members/claim/,      true",
            "/api/v1/tontines/a%62c/members/claim,     true",
            "/api/v1/tontines/abc/members/%63laim,     true",
            "/api/v1/tontines/abc/members/clai%6D/,    true",
            "/api/v1/tontines/abc/members,             false",
            "/api/v1/tontines/abc/members/claims,      false",
            "/api/v1/tontines/abc/members/claim/x,     false",
            "/api/v1/tontines/members/claim,           false",
            "/api/v1/tontines/a/b/members/claim,       false"
    })
    void claimEndpoint_matchesOneIdSegment_evenWhenEncoded(String rawPath, boolean expected) {
        RequestPathMatcher claim = RequestPathMatcher.exactWithOptionalTrailingSlash(
                "/api/v1/tontines/{tontineId}/members/claim");
        assertThat(claim.matches(requestTo(rawPath))).isEqualTo(expected);
    }

    @Test
    void doesNotDecodeTwice() {
        // %2574 = "%74" litteral : ne doit jamais etre pris pour "t" (aucun double decodage).
        // (Le pare-feu de Spring Security rejette de toute facon %25 en amont.)
        assertThat(LOGOUT.matches(requestTo("/api/v1/auth/logou%2574"))).isFalse();
    }

    @Test
    void encodedSlashStaysInsideItsSegment() {
        // %2F ne cree pas de nouveau segment : il ne peut pas faire sortir le chemin de son motif.
        assertThat(LOGOUT.matches(requestTo("/api/v1/auth%2Flogout"))).isFalse();
    }
}
