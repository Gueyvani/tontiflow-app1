package com.tontiflow.interfaces.rest;

import com.tontiflow.UserContext;
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
     * @param request email + mot de passe du futur compte
     * @return {@code 201 Created}, corps vide
     */
    @PostMapping("/register")
    public ResponseEntity<Void> register(@Valid @RequestBody RegisterRequest request) {
        authAccountService.createAccount(request.email(), request.password());
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
     * @param request email + mot de passe fournis pour la tentative
     * @return {@code 200 OK} avec les tokens émis
     */
    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthAccount account = authAccountService.authenticate(request.email(), request.password());
        UserContext context = authAccountService.toUserContext(account);
        String accessToken = accessTokenService.generate(context);
        RefreshToken refreshToken = refreshTokenService.issue(account.getId());

        TokenResponse response = new TokenResponse(
                accessToken, "Bearer", jwtProperties.accessTokenTtl().toSeconds(), refreshToken.getRawToken());
        return ResponseEntity.ok(response);
    }

    /**
     * Renouvelle une session à partir d'un Refresh Token valide : fait
     * tourner le Refresh Token (usage unique) et émet un nouvel Access Token
     * reflétant les rôles/permissions actuels du compte.
     *
     * @param request Refresh Token brut précédemment émis
     * @return {@code 200 OK} avec les nouveaux tokens
     */
    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        RefreshToken rotated = refreshTokenService.rotate(request.refreshToken());
        AuthAccount account = authAccountService.findById(rotated.getAccountId());
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
