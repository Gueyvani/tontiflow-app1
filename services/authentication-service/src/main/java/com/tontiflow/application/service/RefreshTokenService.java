package com.tontiflow.application.service;

import com.tontiflow.application.exception.InvalidRefreshTokenException;
import com.tontiflow.application.exception.RefreshTokenExpiredException;
import com.tontiflow.application.exception.RefreshTokenReuseDetectedException;
import com.tontiflow.domain.model.RefreshToken;
import com.tontiflow.infrastructure.repository.RefreshTokenRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Gère le cycle de vie complet des Refresh Tokens : émission, rotation à
 * usage unique, et détection de réutilisation.
 *
 * <p>Le token brut est généré via {@link SecureRandom} (256 bits, encodage
 * Base64 URL-safe sans padding) et n'est jamais persisté : seul son hash
 * SHA-256 ({@link MessageDigest}) est stocké, ce qui rend une recherche
 * déterministe par hash possible — contrairement à {@code PasswordEncoder}
 * (BCrypt), dont le salage aléatoire empêche toute recherche par valeur et
 * qui n'est de toute façon pas destiné à un secret déjà à haute entropie.</p>
 */
@Service
public class RefreshTokenService {

    private static final String HASH_ALGORITHM = "SHA-256";
    private static final int TOKEN_ENTROPY_BYTES = 32; // 256 bits
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final RefreshTokenRepository refreshTokenRepository;
    private final Clock clock;
    private final Duration refreshTokenTtl;

    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository, Clock clock,
                                @Value("${refresh-token.ttl:30d}") String refreshTokenTtl) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.clock = clock;
        // DurationStyle.detectAndParse comprend le format simplifie ("30d", "15m", ...)
        // deja utilise pour jwt.access-token-ttl, garanti sans ambiguite de conversion @Value.
        this.refreshTokenTtl = DurationStyle.detectAndParse(refreshTokenTtl);
    }

    /**
     * Émet un nouveau Refresh Token pour un compte, ouvrant une nouvelle
     * famille de rotation.
     *
     * @param accountId identifiant du compte pour lequel émettre le token
     * @return le token créé, avec sa valeur brute accessible via {@link RefreshToken#getRawToken()}
     */
    @Transactional
    public RefreshToken issue(UUID accountId) {
        return createAndPersist(accountId, UUID.randomUUID());
    }

    /**
     * Fait tourner un Refresh Token : valide le token présenté, le révoque,
     * et en émet un nouveau dans la même famille.
     *
     * <p>Si le token présenté est déjà révoqué, ceci est traité comme un
     * signal de compromission probable : toute la famille est immédiatement
     * révoquée avant que l'exception ne soit levée.</p>
     *
     * @param rawToken token brut présenté par le client
     * @return le nouveau token émis, avec sa valeur brute accessible via {@link RefreshToken#getRawToken()}
     * @throws InvalidRefreshTokenException      si aucun token ne correspond au hash calculé
     * @throws RefreshTokenExpiredException      si le token est expiré
     * @throws RefreshTokenReuseDetectedException si le token était déjà révoqué (réutilisation détectée)
     */
    // noRollbackFor : sans cela, le rollback par defaut de Spring sur exception non
    // controlee annulerait la revocation de la famille (revokeFamily) executee juste
    // avant de lever RefreshTokenReuseDetectedException, videant la protection de son effet.
    @Transactional(noRollbackFor = RefreshTokenReuseDetectedException.class)
    public RefreshToken rotate(String rawToken) {
        String hash = hash(rawToken);
        RefreshToken presented = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new InvalidRefreshTokenException("Refresh token invalide"));

        Instant now = clock.instant();

        if (presented.getExpiresAt().isBefore(now)) {
            throw new RefreshTokenExpiredException("Refresh token expire");
        }

        if (presented.getRevokedAt() != null) {
            // Un token deja revoque qui reapparait signale un vol probable :
            // toute la lignee est revoquee par defense en profondeur.
            refreshTokenRepository.revokeFamily(presented.getFamilyId(), now);
            throw new RefreshTokenReuseDetectedException("Reutilisation de refresh token detectee");
        }

        presented.setRevokedAt(now);

        return createAndPersist(presented.getAccountId(), presented.getFamilyId());
    }

    /**
     * Révoque la famille de rotation du Refresh Token présenté (logout,
     * décision Q5 — audit Phase Q).
     *
     * <p>Le {@code familyId} révoqué est <b>toujours</b> dérivé du hash du
     * token brut présenté — jamais fourni directement par l'appelant — pour
     * exclure toute possibilité de révoquer arbitrairement la famille d'un
     * autre compte. Réutilise {@link RefreshTokenRepository#revokeFamily}
     * tel quel : sa clause {@code WHERE revoked_at IS NULL} le rend
     * intrinsèquement idempotent (un second appel, un token déjà révoqué ou
     * déjà expiré ne provoquent ni erreur ni réactivation), donc aucune
     * vérification d'expiration ou de révocation préalable n'est nécessaire
     * ici — à la différence de {@link #rotate}, dont la détection de
     * réutilisation répond à une menace différente (émission frauduleuse
     * d'un nouvel Access Token), non pertinente pour une simple déconnexion
     * volontaire.</p>
     *
     * @param rawToken token brut présenté par le client
     * @throws InvalidRefreshTokenException si aucun token ne correspond au hash calculé
     */
    @Transactional
    public void revoke(String rawToken) {
        String hash = hash(rawToken);
        RefreshToken presented = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new InvalidRefreshTokenException("Refresh token invalide"));

        refreshTokenRepository.revokeFamily(presented.getFamilyId(), clock.instant());
    }

    private RefreshToken createAndPersist(UUID accountId, UUID familyId) {
        String rawToken = generateRawToken();
        Instant now = clock.instant();

        RefreshToken token = new RefreshToken();
        token.setAccountId(accountId);
        token.setFamilyId(familyId);
        token.setTokenHash(hash(rawToken));
        token.setIssuedAt(now);
        token.setExpiresAt(now.plus(refreshTokenTtl));

        RefreshToken saved = refreshTokenRepository.save(token);
        saved.setRawToken(rawToken);
        return saved;
    }

    private static String generateRawToken() {
        byte[] entropy = new byte[TOKEN_ENTROPY_BYTES];
        SECURE_RANDOM.nextBytes(entropy);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
    }

    private static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] hashBytes = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(HASH_ALGORITHM + " non disponible sur cette JVM", e);
        }
    }
}
