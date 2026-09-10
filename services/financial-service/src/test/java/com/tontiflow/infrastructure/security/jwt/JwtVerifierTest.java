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
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtVerifierTest {

    private PrivateKey privateKey;
    private PublicKey publicKey;
    private JwtVerifier verifier;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        this.privateKey = keyPair.getPrivate();
        this.publicKey = keyPair.getPublic();
        this.verifier = new JwtVerifier(keyPair.getPublic());
    }

    @Test
    void verify_withValidToken_returnsUserContext() {
        UUID userId = UUID.randomUUID();
        String token = buildToken(privateKey, userId, Instant.now().plus(15, ChronoUnit.MINUTES),
                List.of("ROLE_USER", "ROLE_TREASURER"), List.of("FINANCIAL_READ", "FINANCIAL_WRITE"));

        UserContext userContext = verifier.verify(token);

        assertThat(userContext.userId()).isEqualTo(userId);
        assertThat(userContext.username()).isEqualTo("alice");
        assertThat(userContext.email()).isEqualTo("alice@tontiflow.test");
        assertThat(userContext.roles()).containsExactlyInAnyOrder("ROLE_USER", "ROLE_TREASURER");
        assertThat(userContext.permissions()).containsExactlyInAnyOrder("FINANCIAL_READ", "FINANCIAL_WRITE");
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

    // ------------------------------------------------------------------
    // Décision R11 (corrections techniques) : couvre fromPublicKeyResource
    // /loadPublicKey/decodePem (chargement de la clé publique RSA depuis un
    // PEM), jusqu'ici jamais exercés par aucun test — tous les tests
    // ci-dessus construisent le JwtVerifier directement avec une PublicKey
    // déjà en mémoire, sans jamais passer par le chemin de chargement réel
    // utilisé en production (SecurityConfig, jwt.public-key-location).
    // ------------------------------------------------------------------

    @Test
    void fromPublicKeyResource_withValidPem_producesWorkingVerifier() {
        JwtVerifier loaded = JwtVerifier.fromPublicKeyResource(publicKeyPemResource());
        UUID userId = UUID.randomUUID();
        String token = buildToken(privateKey, userId, Instant.now().plus(15, ChronoUnit.MINUTES),
                List.of("ROLE_USER"), List.of());

        UserContext userContext = loaded.verify(token);

        assertThat(userContext.userId()).isEqualTo(userId);
    }

    @Test
    void fromPublicKeyResource_withBlankContent_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> JwtVerifier.fromPublicKeyResource(new ByteArrayResource("   ".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fromPublicKeyResource_withInvalidBase64Content_throwsIllegalArgumentException() {
        String badPem = "-----BEGIN PUBLIC KEY-----\nceci-nest-pas-du-base64-valide!!!\n-----END PUBLIC KEY-----";

        assertThatThrownBy(() -> JwtVerifier.fromPublicKeyResource(
                new ByteArrayResource(badPem.getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fromPublicKeyResource_withValidBase64ButInvalidKeyData_throwsIllegalArgumentException() {
        String garbageBase64 = Base64.getEncoder().encodeToString("ceci n'est pas une cle RSA".getBytes(StandardCharsets.UTF_8));
        String badPem = "-----BEGIN PUBLIC KEY-----\n" + garbageBase64 + "\n-----END PUBLIC KEY-----";

        assertThatThrownBy(() -> JwtVerifier.fromPublicKeyResource(
                new ByteArrayResource(badPem.getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fromPublicKeyResource_whenResourceUnreadable_throwsIllegalArgumentException() {
        Resource unreadable = new AbstractResource() {
            @Override
            public String getDescription() {
                return "ressource-cassee-pour-test";
            }

            @Override
            public InputStream getInputStream() throws IOException {
                throw new IOException("echec de lecture simule");
            }
        };

        assertThatThrownBy(() -> JwtVerifier.fromPublicKeyResource(unreadable))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Resource publicKeyPemResource() {
        String base64 = Base64.getEncoder().encodeToString(publicKey.getEncoded());
        String pem = "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----\n";
        return new ByteArrayResource(pem.getBytes(StandardCharsets.UTF_8));
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
