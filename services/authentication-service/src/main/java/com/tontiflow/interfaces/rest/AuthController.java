package com.tontiflow.interfaces.rest;

import com.tontiflow.UserContext;
import com.tontiflow.application.exception.AccountDisabledException;
import com.tontiflow.application.exception.AccountLockedException;
import com.tontiflow.application.service.AuthAccountService;
import com.tontiflow.application.service.RefreshTokenService;
import com.tontiflow.domain.model.AuthAccount;
import com.tontiflow.domain.model.RefreshToken;
import com.tontiflow.infrastructure.security.jwt.AccessTokenService;
import com.tontiflow.infrastructure.security.jwt.JwtProperties;
import com.tontiflow.interfaces.rest.dto.LoginRequest;
import com.tontiflow.interfaces.rest.dto.RefreshTokenRequest;
import com.tontiflow.interfaces.rest.dto.RegisterRequest;
import com.tontiflow.interfaces.rest.dto.TokenResponse;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints REST d'authentification : création de compte et connexion.
 *
 * <p>Aucune logique métier n'est portée ici : la création/authentification
 * des comptes reste entièrement de la responsabilité d'{@link AuthAccountService},
 * la génération du JWT de {@link AccessTokenService} (contrats déjà
 * validés, non modifiés par ce contrôleur). Un {@link AuthAccount} n'est
 * jamais retourné directement dans une réponse HTTP.</p>
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthAccountService authAccountService;
    private final AccessTokenService accessTokenService;
    private final JwtProperties jwtProperties;
    private final RefreshTokenService refreshTokenService;

    public AuthController(AuthAccountService authAccountService, AccessTokenService accessTokenService,
                           JwtProperties jwtProperties, RefreshTokenService refreshTokenService) {
        this.authAccountService = authAccountService;
        this.accessTokenService = accessTokenService;
        this.jwtProperties = jwtProperties;
        this.refreshTokenService = refreshTokenService;
    }

    /**
     * Crée un nouveau compte d'authentification. Ne génère aucun JWT :
     * l'inscription et la connexion restent deux actions distinctes.
     *
     * <p><strong>Réponse strictement uniforme (décision R21-D.9, constat
     * D4-05/R21-D.4, Option C)</strong> : que l'email fourni soit disponible,
     * déjà utilisé, ou perdu dans une course concurrente contre la
     * contrainte {@code uk_auth_account_email} ({@link DataIntegrityViolationException},
     * voir {@link AuthAccountService#createAccount}), cette méthode renvoie
     * exactement la même réponse — {@code 201 Created}, corps vide. Aucun
     * signal observable ne permet plus de déterminer si un email est déjà
     * enregistré via cet endpoint.</p>
     *
     * @param request email + mot de passe du futur compte
     * @return {@code 201 Created}, corps vide, dans tous les cas
     */
    @PostMapping("/register")
    public ResponseEntity<Void> register(@Valid @RequestBody RegisterRequest request) {
        try {
            authAccountService.createAccount(request.email(), request.password());
        } catch (DataIntegrityViolationException raceLostAgainstUniqueEmailConstraint) {
            // Course perdue contre uk_auth_account_email : un autre thread a cree ce
            // compte entre existsByEmail() et l'ecriture, dans AuthAccountService.createAccount().
            // Traite exactement comme "email deja pris" - meme reponse, aucune fuite. La
            // transaction de createAccount() est deja entierement rollback par le proxy
            // @Transactional de Spring avant que cette exception n'atteigne ce point : aucun
            // etat "rollback-only" ne fuit jusqu'ici (voir javadoc de createAccount()).
        }
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    /**
     * Authentifie un compte et émet un Access Token JWT.
     *
     * @param request email + mot de passe fournis pour la tentative
     * @return {@code 200 OK} avec le token émis
     */
    /**
     * Authentifie un compte et émet un Access Token JWT accompagné d'un
     * Refresh Token (nouvelle famille de rotation).
     *
     * <p><strong>Décision R21-RD-FU</strong> : authentification et émission
     * du Refresh Token sont désormais réalisées par un <b>unique</b> appel à
     * {@link AuthAccountService#login} (une seule transaction, verrou de
     * ligne) — voir sa javadoc pour la fenêtre de course ainsi fermée.
     * {@code refreshTokenService.issue(...)} n'est plus appelé directement
     * depuis ce contrôleur pour {@code /login}.</p>
     *
     * @param request email + mot de passe fournis pour la tentative
     * @return {@code 200 OK} avec les tokens émis
     */
    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthAccountService.LoginResult result = authAccountService.login(request.email(), request.password());
        UserContext context = authAccountService.toUserContext(result.account());
        String accessToken = accessTokenService.generate(context);

        TokenResponse response = new TokenResponse(
                accessToken, "Bearer", jwtProperties.accessTokenTtl().toSeconds(), result.refreshToken().getRawToken());
        return ResponseEntity.ok(response);
    }

    /**
     * Renouvelle une session à partir d'un Refresh Token valide : fait
     * tourner le Refresh Token (usage unique) et émet un nouvel Access Token
     * reflétant les rôles/permissions actuels du compte.
     *
     * <p><strong>Nettoyage complémentaire (décision R21-RD-FU, Option C)</strong> :
     * si {@link AuthAccountService#findById} rejette ce renouvellement parce
     * que le compte est {@code LOCKED}/{@code DISABLED}, la famille que
     * {@code rotate()} vient de relancer juste au-dessus est explicitement
     * révoquée avant de propager l'erreur — {@code rotated.getAccountId()}
     * est exactement le compte dont le statut vient d'être vérifié, jamais
     * celui d'un autre compte. Ce token n'a de toute façon jamais quitté ce
     * serveur (la réponse HTTP n'est jamais construite sur ce chemin) : cette
     * révocation ferme uniquement la trace résiduelle laissée en base, sans
     * changer le code HTTP retourné à l'appelant.</p>
     *
     * @param request Refresh Token brut précédemment émis
     * @return {@code 200 OK} avec les nouveaux tokens
     */
    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        RefreshToken rotated = refreshTokenService.rotate(request.refreshToken());
        AuthAccount account;
        try {
            account = authAccountService.findById(rotated.getAccountId());
        } catch (AccountLockedException | AccountDisabledException blocked) {
            refreshTokenService.revokeFamilyById(rotated.getFamilyId());
            throw blocked;
        }
        UserContext context = authAccountService.toUserContext(account);
        String accessToken = accessTokenService.generate(context);

        TokenResponse response = new TokenResponse(
                accessToken, "Bearer", jwtProperties.accessTokenTtl().toSeconds(), rotated.getRawToken());
        return ResponseEntity.ok(response);
    }

    /**
     * Déconnecte la session courante en révoquant toute la famille de
     * rotation du Refresh Token présenté (décision Q5, audit Phase Q).
     *
     * <p>Public au même titre que {@code /refresh} : le Refresh Token brut
     * est lui-même le justificatif de l'opération, aucun Access Token n'est
     * requis (cohérent avec le cas d'un Access Token déjà expiré au moment
     * de la déconnexion).</p>
     *
     * @param request Refresh Token brut à révoquer
     * @return {@code 200 OK}, corps vide
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshTokenRequest request) {
        refreshTokenService.revoke(request.refreshToken());
        return ResponseEntity.ok().build();
    }
}
