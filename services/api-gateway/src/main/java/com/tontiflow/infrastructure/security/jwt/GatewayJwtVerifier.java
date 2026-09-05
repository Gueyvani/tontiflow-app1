package com.tontiflow.infrastructure.security.jwt;

import com.tontiflow.UserContext;
import com.tontiflow.security.jwt.JwtClaimNames;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Vérifie localement, côté API Gateway, les Access Token JWT (RS256) émis
 * par {@code authentication-service}.
 *
 * <p>Ne charge et ne manipule jamais que la clé publique RSA : aucune clé
 * privée n'est accessible ici. Le comportement de vérification est en
 * parité exacte avec {@code AccessTokenService.validate()} côté
 * {@code authentication-service} : signature RS256 et expiration sont
 * vérifiées, l'émetteur ({@code iss}) ne l'est volontairement pas.</p>
 */
public final class GatewayJwtVerifier {

    private static final String RSA_ALGORITHM = "RSA";

    private final PublicKey publicKey;

    public GatewayJwtVerifier(PublicKey publicKey) {
        this.publicKey = publicKey;
    }

    /**
     * Construit un {@link GatewayJwtVerifier} en chargeant la clé publique
     * RSA depuis un PEM (format X509). Toute ressource absente ou tout
     * contenu invalide échoue explicitement — aucune valeur par défaut
     * silencieuse.
     */
    public static GatewayJwtVerifier fromPublicKeyResource(Resource publicKeyResource) {
        return new GatewayJwtVerifier(loadPublicKey(publicKeyResource));
    }

    /**
     * Valide la signature et l'expiration du token, puis reconstruit le
     * {@link UserContext} porté par ses claims.
     *
     * @throws io.jsonwebtoken.JwtException si le token est invalide, mal signé,
     *                                      mal formé ou expiré
     */
    public UserContext verify(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(publicKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        UUID userId = UUID.fromString(claims.get(JwtClaimNames.SUBJECT, String.class));
        String username = claims.get(JwtClaimNames.USERNAME, String.class);
        String email = claims.get(JwtClaimNames.EMAIL, String.class);
        Set<String> roles = readStringSet(claims, JwtClaimNames.ROLES);
        Set<String> permissions = readStringSet(claims, JwtClaimNames.PERMISSIONS);

        return new UserContext(userId, username, email, roles, permissions);
    }

    @SuppressWarnings("unchecked")
    private static Set<String> readStringSet(Claims claims, String claimName) {
        List<String> values = claims.get(claimName, List.class);
        return values == null ? Set.of() : Set.copyOf(values);
    }

    private static PublicKey loadPublicKey(Resource pemResource) {
        byte[] derBytes = decodePem(pemResource);
        try {
            KeyFactory keyFactory = KeyFactory.getInstance(RSA_ALGORITHM);
            return keyFactory.generatePublic(new X509EncodedKeySpec(derBytes));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalArgumentException(
                    "Clé publique RSA invalide (attendu : PEM X509) dans " + pemResource, e);
        }
    }

    private static byte[] decodePem(Resource pemResource) {
        String pem;
        try (InputStream inputStream = pemResource.getInputStream()) {
            pem = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("Impossible de lire la ressource " + pemResource, e);
        }

        String base64Only = pem
                .replaceAll("-----BEGIN [A-Z ]+-----", "")
                .replaceAll("-----END [A-Z ]+-----", "")
                .replaceAll("\\s", "");

        if (base64Only.isBlank()) {
            throw new IllegalArgumentException("Contenu PEM vide ou invalide dans " + pemResource);
        }

        try {
            return Base64.getDecoder().decode(base64Only);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Contenu Base64 invalide dans le PEM " + pemResource, e);
        }
    }
}
