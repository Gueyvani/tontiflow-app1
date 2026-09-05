package com.tontiflow.infrastructure.security.jwt;

import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Charge une paire de clés RSA encodées au format PEM depuis une
 * {@link Resource} Spring : PKCS8 (non chiffré) pour la clé privée,
 * X509 pour la clé publique.
 *
 * <p>Cette classe ne génère et ne fournit aucune clé par défaut : elle se
 * limite à décoder un matériel cryptographique déjà existant, dont
 * l'emplacement est externalisé par l'appelant. Toute erreur de format ou
 * d'algorithme est remontée explicitement via {@link IllegalArgumentException},
 * jamais absorbée silencieusement.</p>
 */
public final class RsaKeyLoader {

    private static final String RSA_ALGORITHM = "RSA";

    private RsaKeyLoader() {
        throw new IllegalStateException("Classe utilitaire, non instanciable");
    }

    /**
     * Charge une clé privée RSA à partir d'un PEM au format PKCS8.
     *
     * @param pemResource ressource contenant le PEM de la clé privée
     * @return la clé privée décodée
     * @throws IllegalArgumentException si la ressource est illisible ou si son contenu
     *                                  n'est pas un PEM PKCS8 RSA valide
     */
    public static PrivateKey loadPrivateKey(Resource pemResource) {
        byte[] derBytes = decodePem(pemResource);
        try {
            KeyFactory keyFactory = KeyFactory.getInstance(RSA_ALGORITHM);
            return keyFactory.generatePrivate(new PKCS8EncodedKeySpec(derBytes));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalArgumentException(
                    "Clé privée RSA invalide (attendu : PEM PKCS8) dans " + pemResource, e);
        }
    }

    /**
     * Charge une clé publique RSA à partir d'un PEM au format X509.
     *
     * @param pemResource ressource contenant le PEM de la clé publique
     * @return la clé publique décodée
     * @throws IllegalArgumentException si la ressource est illisible ou si son contenu
     *                                  n'est pas un PEM X509 RSA valide
     */
    public static PublicKey loadPublicKey(Resource pemResource) {
        byte[] derBytes = decodePem(pemResource);
        try {
            KeyFactory keyFactory = KeyFactory.getInstance(RSA_ALGORITHM);
            return keyFactory.generatePublic(new X509EncodedKeySpec(derBytes));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalArgumentException(
                    "Clé publique RSA invalide (attendu : PEM X509) dans " + pemResource, e);
        }
    }

    /**
     * Retire les en-têtes/pieds PEM ({@code -----BEGIN ...-----} /
     * {@code -----END ...-----}) puis décode le Base64 restant en DER.
     * Générique vis-à-vis du libellé PEM (PRIVATE KEY, PUBLIC KEY, ...),
     * qui n'est pas vérifié ici : la validité réelle est tranchée par
     * {@link KeyFactory} lors de la reconstruction de la clé.
     */
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
