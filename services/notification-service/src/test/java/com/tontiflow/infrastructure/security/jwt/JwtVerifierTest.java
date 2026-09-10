package com.tontiflow.infrastructure.security.jwt;

import com.tontiflow.UserContext;
import com.tontiflow.security.jwt.JwtClaimNames;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.SignatureException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtVerifierTest {

    private PrivateKey privateKey;
    private JwtVerifier verifier;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        this.privateKey = keyPair.getPrivate();
        this.verifier = new JwtVerifier(keyPair.getPublic());
    }

    @Test
    void verify_withValidToken_returnsUserContext() {
        UUID userId = UUID.randomUUID();
        String token = buildToken(privateKey, userId, Instant.now().plus(15, ChronoUnit.MINUTES),
                List.of("ROLE_USER", "ROLE_MEMBER"), List.of("NOTIFICATION_READ", "NOTIFICATION_ACK"));

        UserContext userContext = verifier.verify(token);

        assertThat(userContext.userId()).isEqualTo(userId);
        assertThat(userContext.username()).isEqualTo("alice");
        assertThat(userContext.email()).isEqualTo("alice@tontiflow.test");
        assertThat(userContext.roles()).containsExactlyInAnyOrder("ROLE_USER", "ROLE_MEMBER");
        assertThat(userContext.permissions()).containsExactlyInAnyOrder("NOTIFICATION_READ", "NOTIFICATION_ACK");
    }

    @Test
    void verify_withInvalidSignature_throwsSignatureException() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        PrivateKey otherPrivateKey = generator.generateKeyPair().getPrivate();

        String token = buildToken(otherPrivateKey, UUID.randomUUID(), Instant.now().plus(15, ChronoUnit.MINUTES),
                List.of("ROLE_USER"), List.of());

        assertThrows(SignatureException.class, () -> verifier.verify(token));
    }

    @Test
    void verify_withExpiredToken_throwsExpiredJwtException() {
        String token = buildToken(privateKey, UUID.randomUUID(), Instant.now().minus(1, ChronoUnit.MINUTES),
                List.of("ROLE_USER"), List.of());

        assertThrows(ExpiredJwtException.class, () -> verifier.verify(token));
    }

    @Test
    void verify_withMalformedToken_throwsMalformedJwtException() {
        assertThrows(MalformedJwtException.class, () -> verifier.verify("ceci-n-est-pas-un-jwt"));
    }

    @Test
    void verify_whenSubjectAbsent_throwsJwtException() {
        String token = Jwts.builder()
                .claim(JwtClaimNames.EXPIRATION, Date.from(Instant.now().plus(15, ChronoUnit.MINUTES)))
                .claim(JwtClaimNames.USERNAME, "alice")
                .claim(JwtClaimNames.EMAIL, "alice@tontiflow.test")
                .claim(JwtClaimNames.ROLES, List.of("ROLE_USER"))
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();

        assertThrows(JwtException.class, () -> verifier.verify(token));
    }

    @Test
    void verify_whenSubjectBlank_throwsJwtException() {
        String token = Jwts.builder()
                .claim(JwtClaimNames.SUBJECT, "   ")
                .claim(JwtClaimNames.EXPIRATION, Date.from(Instant.now().plus(15, ChronoUnit.MINUTES)))
                .claim(JwtClaimNames.USERNAME, "alice")
                .claim(JwtClaimNames.EMAIL, "alice@tontiflow.test")
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();

        assertThrows(JwtException.class, () -> verifier.verify(token));
    }

    @Test
    void verify_whenSubjectNotUuid_throwsJwtException() {
        String token = Jwts.builder()
                .claim(JwtClaimNames.SUBJECT, "pas-un-uuid")
                .claim(JwtClaimNames.EXPIRATION, Date.from(Instant.now().plus(15, ChronoUnit.MINUTES)))
                .claim(JwtClaimNames.USERNAME, "alice")
                .claim(JwtClaimNames.EMAIL, "alice@tontiflow.test")
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();

        assertThrows(JwtException.class, () -> verifier.verify(token));
    }

    @Test
    void verify_whenPermissionsClaimMissing_returnsEmptyPermissions() {
        UUID userId = UUID.randomUUID();
        // Claim PERMISSIONS volontairement absent (token d'un format anterieur / degrade).
        String token = Jwts.builder()
                .claim(JwtClaimNames.SUBJECT, userId.toString())
                .claim(JwtClaimNames.ISSUED_AT, Date.from(Instant.now()))
                .claim(JwtClaimNames.EXPIRATION, Date.from(Instant.now().plus(15, ChronoUnit.MINUTES)))
                .claim(JwtClaimNames.JWT_ID, UUID.randomUUID().toString())
                .claim(JwtClaimNames.ISSUER, "authentication-service")
                .claim(JwtClaimNames.USERNAME, "alice")
                .claim(JwtClaimNames.EMAIL, "alice@tontiflow.test")
                .claim(JwtClaimNames.ROLES, List.of("ROLE_USER"))
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();

        UserContext userContext = verifier.verify(token);

        assertThat(userContext.permissions()).isEmpty();
        assertThat(userContext.roles()).containsExactly("ROLE_USER");
    }

    private static String buildToken(PrivateKey signingKey, UUID userId, Instant expiresAt,
                                      List<String> roles, List<String> permissions) {
        return Jwts.builder()
                .claim(JwtClaimNames.SUBJECT, userId.toString())
                .claim(JwtClaimNames.ISSUED_AT, Date.from(Instant.now()))
                .claim(JwtClaimNames.EXPIRATION, Date.from(expiresAt))
                .claim(JwtClaimNames.JWT_ID, UUID.randomUUID().toString())
                .claim(JwtClaimNames.ISSUER, "authentication-service")
                .claim(JwtClaimNames.USERNAME, "alice")
                .claim(JwtClaimNames.EMAIL, "alice@tontiflow.test")
                .claim(JwtClaimNames.ROLES, roles)
                .claim(JwtClaimNames.PERMISSIONS, permissions)
                .signWith(signingKey, Jwts.SIG.RS256)
                .compact();
    }
}
