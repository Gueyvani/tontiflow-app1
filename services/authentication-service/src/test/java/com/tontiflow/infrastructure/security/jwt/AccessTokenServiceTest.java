package com.tontiflow.infrastructure.security.jwt;

import com.tontiflow.UserContext;
import com.tontiflow.security.jwt.JwtClaimNames;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.SignatureException;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests unitaires de {@link AccessTokenService}.
 *
 * <p>Chaque test utilise une paire de clés RSA jetable générée en mémoire
 * (aucune clé réelle, aucun fichier) et une {@link Clock} fixe pour rendre
 * la génération/expiration des tokens entièrement déterministe.</p>
 */
class AccessTokenServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void generate_thenValidate_returnsEquivalentUserContext() throws Exception {
        KeyPair keyPair = generateRsaKeyPair();
        AccessTokenService service = newService(keyPair, FIXED_NOW);
        UserContext original = sampleUserContext();

        String token = service.generate(original);
        UserContext restored = service.validate(token);

        assertThat(restored).isEqualTo(original);
    }

    @Test
    void generate_setsAllRequiredClaims() throws Exception {
        KeyPair keyPair = generateRsaKeyPair();
        JwtProperties properties = sampleProperties();
        AccessTokenService service = newService(keyPair, properties, FIXED_NOW);
        UserContext userContext = sampleUserContext();

        String token = service.generate(userContext);

        Claims claims = Jwts.parser()
                .verifyWith(keyPair.getPublic())
                .clock(() -> Date.from(FIXED_NOW))
                .build()
                .parseSignedClaims(token)
                .getPayload();

        assertThat(claims.get(JwtClaimNames.SUBJECT, String.class)).isEqualTo(userContext.userId().toString());
        assertThat(claims.get(JwtClaimNames.ISSUED_AT, Date.class)).isEqualTo(Date.from(FIXED_NOW));
        assertThat(claims.get(JwtClaimNames.EXPIRATION, Date.class))
                .isEqualTo(Date.from(FIXED_NOW.plus(properties.accessTokenTtl())));
        assertThat(claims.get(JwtClaimNames.JWT_ID, String.class)).isNotBlank();
        assertThat(claims.get(JwtClaimNames.ISSUER, String.class)).isEqualTo(properties.issuer());
        assertThat(claims.get(JwtClaimNames.USERNAME, String.class)).isEqualTo(userContext.username());
        assertThat(claims.get(JwtClaimNames.EMAIL, String.class)).isEqualTo(userContext.email());
        assertThat(claims.get(JwtClaimNames.ROLES, List.class)).containsExactlyInAnyOrderElementsOf(userContext.roles());
        assertThat(claims.get(JwtClaimNames.PERMISSIONS, List.class))
                .containsExactlyInAnyOrderElementsOf(userContext.permissions());
    }

    @Test
    void validate_expiredToken_throwsExpiredJwtException() throws Exception {
        KeyPair keyPair = generateRsaKeyPair();
        JwtProperties properties = new JwtProperties(dummyResource(), dummyResource(), "authentication-service",
                Duration.ofMinutes(15));

        // Un token est genere "maintenant", puis valide avec une horloge situee
        // apres son expiration (now + TTL + marge) : le rejet doit etre explicite.
        AccessTokenService generator = newService(keyPair, properties, FIXED_NOW);
        String token = generator.generate(sampleUserContext());

        Instant afterExpiry = FIXED_NOW.plus(properties.accessTokenTtl()).plusSeconds(60);
        AccessTokenService validatorAfterExpiry = newService(keyPair, properties, afterExpiry);

        assertThatThrownBy(() -> validatorAfterExpiry.validate(token))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void validate_signatureFromAnotherKeyPair_throwsSignatureException() throws Exception {
        KeyPair signingKeyPair = generateRsaKeyPair();
        KeyPair unrelatedKeyPair = generateRsaKeyPair();

        AccessTokenService generator = newService(signingKeyPair, FIXED_NOW);
        String token = generator.generate(sampleUserContext());

        // Validation avec la cle publique d'une AUTRE paire : la signature ne doit jamais correspondre.
        AccessTokenService validatorWithWrongKey = newService(unrelatedKeyPair, FIXED_NOW);

        assertThatThrownBy(() -> validatorWithWrongKey.validate(token))
                .isInstanceOf(SignatureException.class);
    }

    @Test
    void validate_whenPermissionsClaimMissing_returnsEmptyPermissions() throws Exception {
        KeyPair keyPair = generateRsaKeyPair();
        AccessTokenService service = newService(keyPair, FIXED_NOW);
        UUID userId = UUID.randomUUID();

        // Claim PERMISSIONS volontairement absent (token d'un format anterieur / degrade) :
        // construit manuellement, sans passer par generate() qui l'ecrit toujours.
        String token = Jwts.builder()
                .claim(JwtClaimNames.SUBJECT, userId.toString())
                .claim(JwtClaimNames.ISSUED_AT, Date.from(FIXED_NOW))
                .claim(JwtClaimNames.EXPIRATION, Date.from(FIXED_NOW.plus(Duration.ofMinutes(15))))
                .claim(JwtClaimNames.JWT_ID, UUID.randomUUID().toString())
                .claim(JwtClaimNames.ISSUER, "authentication-service")
                .claim(JwtClaimNames.USERNAME, "alice")
                .claim(JwtClaimNames.EMAIL, "alice@tontiflow.test")
                .claim(JwtClaimNames.ROLES, List.of("ROLE_USER"))
                .signWith(keyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();

        UserContext userContext = service.validate(token);

        assertThat(userContext.permissions()).isEmpty();
        assertThat(userContext.roles()).containsExactly("ROLE_USER");
    }

    private static AccessTokenService newService(KeyPair keyPair, Instant fixedInstant) {
        return newService(keyPair, sampleProperties(), fixedInstant);
    }

    private static AccessTokenService newService(KeyPair keyPair, JwtProperties properties, Instant fixedInstant) {
        PrivateKey privateKey = keyPair.getPrivate();
        PublicKey publicKey = keyPair.getPublic();
        Clock fixedClock = Clock.fixed(fixedInstant, ZoneOffset.UTC);
        return new AccessTokenService(privateKey, publicKey, properties, fixedClock);
    }

    private static JwtProperties sampleProperties() {
        return new JwtProperties(dummyResource(), dummyResource(), "authentication-service", Duration.ofMinutes(15));
    }

    private static UserContext sampleUserContext() {
        return new UserContext(
                UUID.randomUUID(),
                "alice",
                "alice@tontiflow.test",
                Set.of("ROLE_USER"),
                Set.of("TONTINE_READ", "TONTINE_WRITE")
        );
    }

    private static Resource dummyResource() {
        // Non utilisee par AccessTokenService (qui recoit deja les cles resolues) :
        // seule presente pour satisfaire l'invariant de non-nullite de JwtProperties.
        return new ByteArrayResource(new byte[0]);
    }

    private static KeyPair generateRsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }
}
