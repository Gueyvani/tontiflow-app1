package com.tontiflow.application.service;

import com.tontiflow.UserContext;
import com.tontiflow.application.exception.AccountDisabledException;
import com.tontiflow.application.exception.AccountLockedException;
import com.tontiflow.application.exception.AccountNotFoundException;
import com.tontiflow.application.exception.AccountNotFoundInAdminException;
import com.tontiflow.application.exception.DuplicateEmailException;
import com.tontiflow.application.exception.InvalidCredentialsException;
import com.tontiflow.application.exception.RoleAlreadyAssignedException;
import com.tontiflow.application.exception.RoleNotAssignedException;
import com.tontiflow.application.exception.RoleNotFoundException;
import com.tontiflow.domain.enums.AccountStatus;
import com.tontiflow.domain.model.AuthAccount;
import com.tontiflow.domain.model.Permission;
import com.tontiflow.domain.model.Role;
import com.tontiflow.infrastructure.repository.AuthAccountRepository;
import com.tontiflow.infrastructure.repository.RoleRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Gère le cycle de vie des credentials d'un {@link AuthAccount} : création,
 * recherche, authentification et conversion vers le contrat partagé
 * {@link UserContext}.
 *
 * <p>Cette classe est le seul point d'accès aux identifiants de connexion
 * (email + hash de mot de passe) d'{@code authentication-service}. Elle ne
 * génère aucun JWT — cette responsabilité reste exclusivement celle
 * d'{@code AccessTokenService} (contrat déjà validé, non modifié ici).</p>
 */
@Service
public class AuthAccountService {

    private final AuthAccountRepository authAccountRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final int maxFailedAttempts;
    private final Duration failureWindow;
    private final Duration lockDuration;

    /**
     * @param maxFailedAttempts nombre d'échecs consécutifs déclenchant le verrouillage
     *                          temporisé (décision R21-D.3, défaut 5)
     * @param failureWindow     fenêtre glissante au-delà de laquelle le compteur d'échecs
     *                          repart à 1 au lieu de s'incrémenter (défaut 15 min), format
     *                          simplifié Spring Boot ("15m") — même convention que
     *                          {@code refresh-token.ttl}
     * @param lockDuration      durée du verrouillage temporisé une fois déclenché (défaut
     *                          15 min), même format simplifié
     */
    public AuthAccountService(AuthAccountRepository authAccountRepository, RoleRepository roleRepository,
                               PasswordEncoder passwordEncoder, Clock clock,
                               @Value("${account-lockout.max-failed-attempts:5}") int maxFailedAttempts,
                               @Value("${account-lockout.failure-window:15m}") String failureWindow,
                               @Value("${account-lockout.lock-duration:15m}") String lockDuration) {
        this.authAccountRepository = authAccountRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.maxFailedAttempts = maxFailedAttempts;
        // DurationStyle.detectAndParse comprend le format simplifie ("15m", "15s", ...)
        // deja utilise pour jwt.access-token-ttl / refresh-token.ttl, garanti sans
        // ambiguite de conversion @Value (meme motif que RefreshTokenService).
        this.failureWindow = DurationStyle.detectAndParse(failureWindow);
        this.lockDuration = DurationStyle.detectAndParse(lockDuration);
    }

    /**
     * Crée un nouveau compte d'authentification, statut initial {@link AccountStatus#ACTIVE}.
     *
     * <p>Le mot de passe fourni en clair n'est jamais persisté : seul son
     * hash BCrypt ({@link PasswordEncoder}) est stocké dans
     * {@code AuthAccount.passwordHash}.</p>
     *
     * @param email       email du nouveau compte, doit être unique
     * @param rawPassword mot de passe en clair, haché avant persistance
     * @return le compte créé et persisté
     * @throws DuplicateEmailException si un compte existe déjà pour cet email
     */
    @Transactional
    public AuthAccount createAccount(String email, String rawPassword) {
        if (authAccountRepository.existsByEmail(email)) {
            throw new DuplicateEmailException("Un compte existe deja pour cet email");
        }

        AuthAccount account = new AuthAccount();
        account.setEmail(email);
        account.setPasswordHash(passwordEncoder.encode(rawPassword));
        account.setStatus(AccountStatus.ACTIVE);

        return authAccountRepository.save(account);
    }

    /**
     * Recherche un compte par email, sans vérification de mot de passe ni de statut.
     *
     * @param email email recherché
     * @return le compte correspondant, ou {@link Optional#empty()} si aucun
     */
    public Optional<AuthAccount> findByEmail(String email) {
        return authAccountRepository.findByEmail(email);
    }

    /**
     * Recherche un compte par son identifiant.
     *
     * @param id identifiant du compte
     * @return le compte correspondant
     * @throws AccountNotFoundInAdminException si aucun compte ne correspond à cet identifiant
     */
    @Transactional(readOnly = true)
    public AuthAccount findById(UUID id) {
        AuthAccount account = authAccountRepository.findById(id)
                .orElseThrow(() -> new AccountNotFoundInAdminException("Compte introuvable"));
        // Meme necessite que dans authenticate(...) : roles/permissions (LAZY) doivent
        // etre initialisees pendant que la session est active, l'appelant (ex. le flux
        // refresh d'AuthController) appelant toUserContext(...) hors de cette transaction.
        initializeRolesAndPermissions(account);
        return account;
    }

    /**
     * Authentifie un compte à partir de son email et de son mot de passe en clair.
     *
     * <p>La comparaison du mot de passe est déléguée entièrement à
     * {@link PasswordEncoder#matches(CharSequence, String)} — aucune comparaison
     * manuelle n'est effectuée. Chaque cause d'échec (compte introuvable, mot
     * de passe incorrect, statut bloquant) est signalée par une exception
     * distincte, afin qu'une phase ultérieure (contrôleur de login) puisse
     * décider du traitement approprié.</p>
     *
     * <p><strong>Verrouillage temporisé de compte (décision R21-D.3)</strong> :
     * après {@link #maxFailedAttempts} échecs consécutifs dans la fenêtre
     * {@link #failureWindow}, le compte est automatiquement bloqué pendant
     * {@link #lockDuration}, complétant le rate limiting IP du Gateway
     * ({@code AuthIpRateLimitFilter}, R21-D.2) par une protection par
     * compte (credential-stuffing distribué, attaque lente). Ce blocage
     * automatique est <b>volontairement indiscernable</b> d'un mot de passe
     * incorrect : même exception ({@link InvalidCredentialsException}), même
     * message, même coût (le hachage du mot de passe est toujours exécuté,
     * qu'un blocage soit actif ou non) — afin de ne jamais révéler à un
     * tiers qu'un compte donné existe et a été bloqué. Il est strictement
     * distinct du verrouillage <b>manuel/administratif</b>
     * ({@link AccountStatus#LOCKED}, {@link AccountLockedException}, HTTP
     * 423), inchangé, qui reste réservé à une action explicite d'un
     * administrateur déjà informé de l'existence du compte.</p>
     *
     * @param email       email du compte
     * @param rawPassword mot de passe en clair fourni pour la tentative
     * @return le compte authentifié (statut {@link AccountStatus#ACTIVE})
     * @throws AccountNotFoundException     si aucun compte ne correspond à l'email
     * @throws InvalidCredentialsException  si le mot de passe ne correspond pas,
     *                                      ou si un verrouillage temporisé est actif
     * @throws AccountLockedException       si le compte est {@link AccountStatus#LOCKED}
     *                                      (verrouillage manuel/administratif)
     * @throws AccountDisabledException     si le compte est {@link AccountStatus#DISABLED}
     */
    // noRollbackFor : meme necessite que RefreshTokenService.rotate() (reuse detection) -
    // sans cela, l'enregistrement de l'echec (compteur, verrouillage temporise) serait
    // annule par le rollback par defaut de Spring sur RuntimeException au moment meme ou
    // InvalidCredentialsException est levee juste apres, videant le verrouillage de tout effet.
    @Transactional(noRollbackFor = InvalidCredentialsException.class)
    public AuthAccount authenticate(String email, String rawPassword) {
        AuthAccount account = authAccountRepository.findByEmail(email)
                .orElseThrow(() -> new AccountNotFoundException("Aucun compte pour cet email"));

        // Toujours execute, meme si un verrouillage temporise est deja actif : le cout
        // (hachage BCrypt) doit rester identique dans tous les cas ou l'email existe,
        // pour ne jamais laisser un ecart de latence reveler l'etat de verrouillage.
        boolean passwordMatches = passwordEncoder.matches(rawPassword, account.getPasswordHash());
        Instant now = clock.instant();

        if (!passwordMatches) {
            // Correction P1 (audit R21-D.3) : l'enregistrement de l'echec est delegue a un
            // UNIQUE UPDATE atomique et conditionnel cote base (voir
            // AuthAccountRepository.registerFailedAttempt) - aucune lecture Java
            // intermediaire du compteur, donc aucun lost update possible sous concurrence
            // (deux executions concurrentes sur le meme compte sont serialisees par le
            // verrou de ligne pris par l'UPDATE lui-meme). Le WHERE de cette requete exclut
            // deja les comptes sous verrouillage actif (aucune prolongation) : la valeur de
            // retour n'a pas besoin d'etre interpretee ici, la reponse est identique dans
            // tous les cas (generique, ci-dessous).
            authAccountRepository.registerFailedAttempt(account.getId(), now, now.minus(failureWindow),
                    maxFailedAttempts, now.plus(lockDuration));
            throw new InvalidCredentialsException("Mot de passe incorrect");
        }

        // Mot de passe correct : la remise a zero est elle-meme conditionnee, dans le MEME
        // UPDATE atomique, a l'absence de verrouillage actif AU MOMENT REEL de l'ecriture
        // (et non au moment du SELECT ci-dessus, qui peut etre perime sous concurrence -
        // voir AuthAccountRepository.resetFailedAttemptsIfNotLocked). 0 ligne affectee =
        // le compte etait verrouille au moment de l'ecriture -> meme reponse generique
        // qu'un mot de passe incorrect (jamais AccountLockedException/423 ici).
        int reset = authAccountRepository.resetFailedAttemptsIfNotLocked(account.getId(), now);
        if (reset == 0) {
            throw new InvalidCredentialsException("Mot de passe incorrect");
        }

        switch (account.getStatus()) {
            case LOCKED -> throw new AccountLockedException("Compte verrouille");
            case DISABLED -> throw new AccountDisabledException("Compte desactive");
            case ACTIVE -> {
                // Authentification acceptee.
            }
        }

        // AuthAccount.roles et Role.permissions sont LAZY : la session Hibernate se
        // termine avec cette methode transactionnelle, alors que toUserContext(...) est
        // appelee plus tard par l'appelant (ex. AuthController), hors de toute session.
        // On force donc ici l'initialisation des deux niveaux de collection pendant que
        // la session est encore ouverte, sans changer le mapping (toujours LAZY) ni la
        // frontiere transactionnelle (qui reste dans ce service, pas dans le controleur).
        //
        // Note deliberee : les @Modifying ci-dessus n'utilisent PAS clearAutomatically -
        // cela detacherait "account" du contexte de persistance et casserait precisement
        // cette initialisation LAZY juste en dessous (LazyInitializationException). Comme
        // aucun code de cette methode ne relit failedAttempts/lastFailedLoginAt/lockedUntil
        // sur l'instance "account" apres les UPDATE (leur etat en memoire peut donc etre
        // perime sans consequence), l'absence de clearAutomatically est sans risque ici.
        initializeRolesAndPermissions(account);

        return account;
    }

    private static void initializeRolesAndPermissions(AuthAccount account) {
        for (Role role : account.getRoles()) {
            role.getPermissions().size();
        }
    }

    /**
     * Construit le {@link UserContext} porté par un compte authentifié, en
     * aplatissant ses rôles et les permissions de ces rôles.
     *
     * <p>{@code AuthAccount} ne possède pas de champ {@code username} distinct :
     * conformément à la décision validée pour cette phase, l'email du compte
     * est utilisé à la fois comme {@code username} et comme {@code email} du
     * {@link UserContext}. Aucun JWT n'est généré par cette méthode.</p>
     *
     * @param account compte dont le contexte doit être construit
     * @return le contexte utilisateur correspondant, prêt pour {@code AccessTokenService.generate(...)}
     */
    public UserContext toUserContext(AuthAccount account) {
        Set<String> roleNames = account.getRoles().stream()
                .map(Role::getName)
                .collect(Collectors.toSet());

        // Les permissions sont aplaties depuis chaque role porte par le compte ;
        // le Set garantit la deduplication si plusieurs roles partagent une permission.
        Set<String> permissionNames = account.getRoles().stream()
                .flatMap(role -> role.getPermissions().stream())
                .map(Permission::getName)
                .collect(Collectors.toSet());

        return new UserContext(
                account.getId(),
                account.getEmail(),
                account.getEmail(),
                roleNames,
                permissionNames
        );
    }

    /**
     * Attribue un rôle existant à un compte existant.
     *
     * <p>{@code AuthAccount.roles} est la relation dont {@code AuthAccount} est
     * propriétaire : l'attribution est donc gérée ici plutôt que dans un
     * service dédié, cohérent avec la responsabilité déjà portée par cette
     * classe sur le compte.</p>
     *
     * @param accountId identifiant du compte
     * @param roleId    identifiant du rôle à attribuer
     * @throws AccountNotFoundInAdminException si le compte n'existe pas
     * @throws RoleNotFoundException           si le rôle n'existe pas
     * @throws RoleAlreadyAssignedException    si le compte possède déjà ce rôle
     */
    @Transactional
    public void assignRole(UUID accountId, UUID roleId) {
        AuthAccount account = authAccountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundInAdminException("Compte introuvable"));
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new RoleNotFoundException("Role introuvable"));

        // Comparaison par identifiant : AuthAccount/Role ne redefinissent pas
        // equals()/hashCode(), Set.contains(role) ne serait pas fiable ici.
        boolean alreadyAssigned = account.getRoles().stream()
                .anyMatch(r -> r.getId().equals(role.getId()));
        if (alreadyAssigned) {
            throw new RoleAlreadyAssignedException("Ce role est deja attribue a ce compte");
        }

        account.getRoles().add(role);
    }

    /**
     * Retire un rôle d'un compte.
     *
     * @param accountId identifiant du compte
     * @param roleId    identifiant du rôle à retirer
     * @throws AccountNotFoundInAdminException si le compte n'existe pas
     * @throws RoleNotAssignedException        si le compte ne possède pas ce rôle
     */
    @Transactional
    public void removeRole(UUID accountId, UUID roleId) {
        AuthAccount account = authAccountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundInAdminException("Compte introuvable"));

        boolean removed = account.getRoles().removeIf(r -> r.getId().equals(roleId));
        if (!removed) {
            throw new RoleNotAssignedException("Ce role n'est pas attribue a ce compte");
        }
    }
}
