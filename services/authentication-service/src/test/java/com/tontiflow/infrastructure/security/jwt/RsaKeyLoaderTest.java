package com.tontiflow.infrastructure.security.jwt;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests unitaires de {@link RsaKeyLoader}.
 *
 * <p>Aucune clé réelle n'est utilisée : chaque test génère sa propre paire
 * RSA jetable en mémoire via {@link KeyPairGenerator}, l'encode en PEM,
 * puis vérifie que le chargement produit une clé fonctionnellement correcte
 * (signature/vérification round-trip), et non un simple objet non nul.</p>
 */
class RsaKeyLoaderTest {

    @Test
    void loadPrivateKey_withValidPkcs8Pem_returnsUsablePrivateKey() throws Exception {
        KeyPair keyPair = generateRsaKeyPair();
        Resource privatePem = toPem("PRIVATE KEY", keyPair.getPrivate().getEncoded());

        PrivateKey loaded = RsaKeyLoader.loadPrivateKey(privatePem);

        assertThat(loaded.getAlgorithm()).isEqualTo("RSA");
        // La clé chargée doit produire des signatures verifiables par la cle publique d'origine.
        assertThat(canSignAndVerify(loaded, keyPair.getPublic())).isTrue();
    }

    @Test
    void loadPublicKey_withValidX509Pem_returnsUsablePublicKey() throws Exception {
        KeyPair keyPair = generateRsaKeyPair();
        Resource publicPem = toPem("PUBLIC KEY", keyPair.getPublic().getEncoded());

        PublicKey loaded = RsaKeyLoader.loadPublicKey(publicPem);

        assertThat(loaded.getAlgorithm()).isEqualTo("RSA");
        assertThat(canSignAndVerify(keyPair.getPrivate(), loaded)).isTrue();
    }

    @Test
    void loadPrivateKey_withGarbageContent_throwsIllegalArgumentException() {
        Resource invalid = new ByteArrayResource("ceci n'est pas un PEM".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> RsaKeyLoader.loadPrivateKey(invalid))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void loadPublicKey_withGarbageContent_throwsIllegalArgumentException() {
        Resource invalid = new ByteArrayResource("ceci n'est pas un PEM".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> RsaKeyLoader.loadPublicKey(invalid))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void loadPrivateKey_withWrongKeyFormat_throwsIllegalArgumentException() throws Exception {
        // Une cle publique X509 presentee comme si c'etait une cle privee PKCS8 : format incompatible.
        KeyPair keyPair = generateRsaKeyPair();
        Resource publicAsPrivate = toPem("PRIVATE KEY", keyPair.getPublic().getEncoded());

        assertThatThrownBy(() -> RsaKeyLoader.loadPrivateKey(publicAsPrivate))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void loadPublicKey_withWrongKeyFormat_throwsIllegalArgumentException() throws Exception {
        // Symetrique au test precedent : une cle privee PKCS8 presentee comme si c'etait une cle publique X509.
        KeyPair keyPair = generateRsaKeyPair();
        Resource privateAsPublic = toPem("PUBLIC KEY", keyPair.getPrivate().getEncoded());

        assertThatThrownBy(() -> RsaKeyLoader.loadPublicKey(privateAsPublic))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void loadPrivateKey_withBlankPemContent_throwsIllegalArgumentException() {
        Resource blank = new ByteArrayResource("   \n\t  ".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> RsaKeyLoader.loadPrivateKey(blank))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vide");
    }

    private static KeyPair generateRsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static Resource toPem(String label, byte[] derBytes) {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(derBytes);
        String pem = "-----BEGIN " + label + "-----\n" + base64 + "\n-----END " + label + "-----\n";
        return new ByteArrayResource(pem.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean canSignAndVerify(PrivateKey privateKey, PublicKey publicKey) throws Exception {
        byte[] payload = "round-trip".getBytes(StandardCharsets.UTF_8);

        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(privateKey);
        signer.update(payload);
        byte[] signature = signer.sign();

        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(publicKey);
        verifier.update(payload);
        return verifier.verify(signature);
    }
}
