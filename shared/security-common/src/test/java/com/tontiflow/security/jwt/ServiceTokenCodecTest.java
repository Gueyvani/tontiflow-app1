package com.tontiflow.security.jwt;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests de {@link ServiceTokenCodec} (décision F-8). Aucune assertion de durée réelle : horloge injectée. */
class ServiceTokenCodecTest {

    private static final String SECRET = "unit-test-service-token-secret-0123456789";
    private static final String ISS = ServiceTokenCodec.SERVICE_TONTINE;
    private static final String AUD = ServiceTokenCodec.SERVICE_FINANCIAL;

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };
    private final ServiceTokenCodec codec = new ServiceTokenCodec(SECRET, clock);

    @Test
    void issuedToken_isVerified_withExpectedClaims() {
        UUID user = UUID.randomUUID();
        String token = codec.issue(ISS, AUD, List.of(ServiceTokenCodec.SCOPE_LEDGER_WRITE), user);

        ServiceTokenCodec.ServiceTokenClaims claims = codec.verify(token, ISS, AUD);

        assertThat(claims.issuer()).isEqualTo(ISS);
        assertThat(claims.subject()).isEqualTo("service:" + ISS);
        assertThat(claims.scopes()).containsExactly(ServiceTokenCodec.SCOPE_LEDGER_WRITE);
        assertThat(claims.onBehalfOf()).isEqualTo(user);
        assertThat(claims.tokenId()).isNotBlank();
    }

    @Test
    void onBehalfOf_isOptional() {
        String token = codec.issue(ISS, AUD, List.of(ServiceTokenCodec.SCOPE_LEDGER_READ), null);

        assertThat(codec.verify(token, ISS, AUD).onBehalfOf()).isNull();
    }

    @Test
    void multipleScopes_areRoundTripped() {
        String token = codec.issue(ISS, AUD,
                List.of(ServiceTokenCodec.SCOPE_LEDGER_READ, ServiceTokenCodec.SCOPE_LEDGER_WRITE), null);

        assertThat(codec.verify(token, ISS, AUD).scopes())
                .isEqualTo(Set.of(ServiceTokenCodec.SCOPE_LEDGER_READ, ServiceTokenCodec.SCOPE_LEDGER_WRITE));
    }

    @Test
    void tokenSignedWithAnotherSecret_isRejected() {
        ServiceTokenCodec other = new ServiceTokenCodec("another-secret-another-secret-0123456789", clock);
        String token = other.issue(ISS, AUD, List.of(ServiceTokenCodec.SCOPE_LEDGER_WRITE), null);

        assertThatThrownBy(() -> codec.verify(token, ISS, AUD)).isInstanceOf(JwtException.class);
    }

    @Test
    void tamperedPayload_isRejected() {
        String token = codec.issue(ISS, AUD, List.of(ServiceTokenCodec.SCOPE_LEDGER_READ), null);
        String[] parts = token.split("\\.");
        String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"iss\":\"tontine-service\",\"aud\":\"financial-service\",\"sub\":\"service:tontine-service\",\"scope\":\"ledger.write\"}"
                        .getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> codec.verify(parts[0] + "." + forgedPayload + "." + parts[2], ISS, AUD))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void expiredToken_isRejected_afterTtlPlusSkew() {
        String token = codec.issue(ISS, AUD, List.of(ServiceTokenCodec.SCOPE_LEDGER_WRITE), null);

        now.set(now.get().plusSeconds(ServiceTokenCodec.TOKEN_TTL_SECONDS + 31));

        assertThatThrownBy(() -> codec.verify(token, ISS, AUD)).isInstanceOf(JwtException.class);
    }

    @Test
    void token_isStillAccepted_withinClockSkewAfterExpiration() {
        String token = codec.issue(ISS, AUD, List.of(ServiceTokenCodec.SCOPE_LEDGER_WRITE), null);

        now.set(now.get().plusSeconds(ServiceTokenCodec.TOKEN_TTL_SECONDS + 10));

        assertThatCode(() -> codec.verify(token, ISS, AUD)).doesNotThrowAnyException();
    }

    @Test
    void wrongAudience_isRejected() {
        String token = codec.issue(ISS, "credit-service", List.of(ServiceTokenCodec.SCOPE_LEDGER_WRITE), null);

        assertThatThrownBy(() -> codec.verify(token, ISS, AUD)).isInstanceOf(JwtException.class);
    }

    @Test
    void wrongIssuer_isRejected() {
        String token = codec.issue("user-service", AUD, List.of(ServiceTokenCodec.SCOPE_LEDGER_WRITE), null);

        assertThatThrownBy(() -> codec.verify(token, ISS, AUD)).isInstanceOf(JwtException.class);
    }

    @Test
    void userStyleRs256Token_isRejected() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        String rs256 = Jwts.builder()
                .issuer(ISS).audience().add(AUD).and().subject("service:" + ISS)
                .issuedAt(Date.from(now.get())).expiration(Date.from(now.get().plusSeconds(60)))
                .claim("scope", "ledger.write")
                .signWith(pair.getPrivate(), Jwts.SIG.RS256).compact();

        assertThatThrownBy(() -> codec.verify(rs256, ISS, AUD)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void unsignedToken_isRejected() {
        String unsigned = Jwts.builder()
                .issuer(ISS).audience().add(AUD).and().subject("service:" + ISS)
                .issuedAt(Date.from(now.get())).expiration(Date.from(now.get().plusSeconds(60)))
                .claim("scope", "ledger.write")
                .compact();

        assertThatThrownBy(() -> codec.verify(unsigned, ISS, AUD)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void differentHmacAlgorithmWithSameKey_isRejected() {
        // Secret de 64 octets : assez long pour que HS512 soit signable avec la meme cle.
        String longSecret = "long-secret-long-secret-long-secret-long-secret-long-secret-0123";
        ServiceTokenCodec longCodec = new ServiceTokenCodec(longSecret, clock);
        String hs512 = Jwts.builder()
                .issuer(ISS).audience().add(AUD).and().subject("service:" + ISS)
                .issuedAt(Date.from(now.get())).expiration(Date.from(now.get().plusSeconds(60)))
                .claim("scope", "ledger.write")
                .signWith(Keys.hmacShaKeyFor(longSecret.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS512).compact();

        assertThatThrownBy(() -> longCodec.verify(hs512, ISS, AUD)).isInstanceOf(JwtException.class);
    }

    @Test
    void excessiveLifetime_isRejected_evenWhenCorrectlySigned() {
        String longLived = Jwts.builder()
                .issuer(ISS).audience().add(AUD).and().subject("service:" + ISS)
                .issuedAt(Date.from(now.get())).expiration(Date.from(now.get().plus(Duration.ofHours(1))))
                .claim("scope", "ledger.write")
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256).compact();

        assertThatThrownBy(() -> codec.verify(longLived, ISS, AUD)).isInstanceOf(JwtException.class);
    }

    @Test
    void wrongSubject_isRejected() {
        String token = Jwts.builder()
                .issuer(ISS).audience().add(AUD).and().subject("someone-else")
                .issuedAt(Date.from(now.get())).expiration(Date.from(now.get().plusSeconds(60)))
                .claim("scope", "ledger.write")
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256).compact();

        assertThatThrownBy(() -> codec.verify(token, ISS, AUD)).isInstanceOf(JwtException.class);
    }

    @Test
    void garbageToken_isRejected() {
        assertThatThrownBy(() -> codec.verify("not-a-jwt", ISS, AUD)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void missingOrShortSecret_failsFast_withoutEchoingTheSecret() {
        String shortSecret = "too-short-secret";

        assertThatThrownBy(() -> new ServiceTokenCodec(shortSecret, clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(shortSecret);
        assertThatThrownBy(() -> new ServiceTokenCodec(null, clock)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ServiceTokenCodec("", clock)).isInstanceOf(IllegalArgumentException.class);
    }
}
