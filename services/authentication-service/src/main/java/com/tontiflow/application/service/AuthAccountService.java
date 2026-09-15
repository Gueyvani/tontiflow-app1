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

    /** Table de délai fixe (décision R21-D.5, non configurable) — voir {@link AuthAccountRepository#registerFailedAttempt}. */
    private static final Duration DELAY_AT_THREE_FAILURES = Duration.ofSeconds(2);
    private static final Duration DELAY_AT_FOUR_FAILURES = Duration.ofSeconds(5);
    private static final Duration DELAY_AT_FIVE_FAILURES = Duration.ofSeconds(10);
    private static final Duration DELAY_AT_SIX_OR_MORE_FAILURES = Duration.ofSeconds(30);

    /**
     * Mot de passe arbitraire utilisé uniquement pour produire le hash factice
     * ci-dessous (décision R21-D.8, constat D4-04/R21-D.4) — jamais comparé à
     * un mot de passe réel, ne correspond à aucun compte.
     */
    private static final String DUMMY_PASSWORD_FOR_TIMING = "R21-D8-dummy-password-never-used-for-real-auth";

    private final AuthAccountRepository authAccountRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final Duration failureWindow;

    /**
     * Hash BCrypt factice (décision R21-D.8, constat D4-04/R21-D.4 — oracle de
     * timing sur email inconnu), calculé <b>une seule fois</b> ici, à la
     * construction de ce bean singleton, via le {@link PasswordEncoder}
     * réellement configuré — jamais recalculé par requête. Utilisé
     * exclusivement pour que le chemin « email inconnu » exécute un coût
     * BCrypt comparable à celui du chemin « email connu », sans jamais
     * authentifier qui que ce soit ni correspondre à un compte réel. Suit
     * automatiquement toute évolution future de
     * {@code security.password.bcrypt-strength} (recalculé à chaque
     * démarrage avec l'encodeur courant), sans valeur à resynchroniser
     * manuellement.
     */
    private final String dummyPasswordHash;

    /**
     * @param failureWindow fenêtre glissante au-delà de laquelle le compteur d'échecs
     *                      repart à 1 au lieu de s'incrémenter (défaut 15 min), format
     *                      simplifié Spring Boot ("15m") — même convention que
     *                      {@code refresh-token.ttl}
     */
    public AuthAccountService(AuthAccountRepository authAccountRepository, RoleRepository roleRepository,
                               PasswordEncoder passwordEncoder, Clock clock,
                               @Value("${account-lockout.failure-window:15m}") String failureWindow) {
        this.authAccountRepository = authAccountRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        // DurationStyle.detectAndParse comprend le format simplifie ("15m", "15s", ...)
        // deja utilise pour jwt.access-token-ttl / refresh-token.ttl, garanti sans
        // ambiguite de conversion @Value (meme motif que RefreshTokenService).
        this.failureWindow = DurationStyle.detectAndParse(failureWindow);
        this.dummyPasswordHash = passwordEncoder.encode(DUMMY_PASSWORD_FOR_TIMING);
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
     * <p><strong>Ralentissement progressif (décision R21-D.5)</strong> : remplace le
     * verrouillage dur de R21-D.3, qui permettait à quiconque connaissant un email
     * de rendre un compte totalement inaccessible 15 minutes (constat D4-01,
     * audit R21-D.4). Règle fondamentale, sans exception : <b>un mot de passe
     * correct est toujours accepté immédiatement</b>, quel que soit l'état du
     * ralentissement — voir le bloc {@code if (passwordMatches)} ci-dessous, qui
     * ne consulte jamais {@code nextAttemptAllowedAt}. Seul un mot de passe
     * <b>incorrect</b> peut déclencher/prolonger un délai avant la tentative
     * suivante (table fixe, voir {@link AuthAccountRepository#registerFailedAttempt}).
     * Ce ralentissement est <b>volontairement indiscernable</b> d'un simple mot
     * de passe incorrect : même exception ({@link InvalidCredentialsException}),
     * même message, même coût (le hachage du mot de passe est toujours exécuté
     * avant toute décision). Strictement distinct du verrouillage
     * <b>manuel/administratif</b> ({@link AccountStatus#LOCKED},
     * {@link AccountLockedException}, HTTP 423), inchangé.</p>
     *
     * <p><strong>Oracle de timing sur email inconnu (décision R21-D.8, constat
     * D4-04/R21-D.4)</strong> : un email inconnu exécute désormais, lui aussi,
     * un appel {@code passwordEncoder.matches(...)} — contre {@link #dummyPasswordHash},
     * un hash factice sans rapport avec un compte réel — avant de lever
     * {@link AccountNotFoundException}. Objectif unique : rendre ce chemin
     * comparable en coût BCrypt au chemin « email connu », qui exécute déjà
     * systématiquement ce même calcul. Un résidu de latence lié à l'écriture
     * DB de {@link AuthAccountRepository#registerFailedAttempt} (absente sur
     * ce chemin) reste possible mais est très largement réduit par rapport à
     * l'écart initial (mesure R21-D.8, Phase B.1 : de l'ordre de 300+ ms avant
     * remédiation, à quelques centaines de microsecondes à quelques
     * millisecondes après — non éliminé à 100 %, mais non exploitable de
     * façon fiable dans le modèle de menace retenu). Ce chemin n'accède à
     * {@link AuthAccountRepository} qu'en lecture ({@code findByEmail}) :
     * aucun état (compteur, délai R21-D.5) n'est jamais créé pour un email
     * inexistant.</p>
     *
     * @param email       email du compte
     * @param rawPassword mot de passe en clair fourni pour la tentative
     * @return le compte authentifié (statut {@link AccountStatus#ACTIVE})
     * @throws AccountNotFoundException     si aucun compte ne correspond à l'email
     * @throws InvalidCredentialsException  si le mot de passe ne correspond pas
     * @throws AccountLockedException       si le compte est {@link AccountStatus#LOCKED}
     *                                      (verrouillage manuel/administratif)
     * @throws AccountDisabledException     si le compte est {@link AccountStatus#DISABLED}
     */
    // noRollbackFor : meme necessite que RefreshTokenService.rotate() (reuse detection) -
    // sans cela, l'enregistrement de l'echec (compteur, delai) serait annule par le
    // rollback par defaut de Spring sur RuntimeException au moment meme ou
    // InvalidCredentialsException est levee juste apres, videant le ralentissement de tout effet.
    @Transactional(noRollbackFor = InvalidCredentialsException.class)
    public AuthAccount authenticate(String email, String rawPassword) {
        Optional<AuthAccount> maybeAccount = authAccountRepository.findByEmail(email);
        if (maybeAccount.isEmpty()) {
            // Oracle de timing (decision R21-D.8, constat D4-04/R21-D.4) : meme cout BCrypt
            // que le chemin email connu, contre un hash factice sans rapport avec un compte
            // reel (voir dummyPasswordHash) - resultat delibrement ignore, seul le cout
            // d'execution compte ici. Aucun acces a AuthAccountRepository au-dela du
            // findByEmail ci-dessus : aucun etat R21-D.5 (compteur, delai) n'est cree.
            passwordEncoder.matches(rawPassword, dummyPasswordHash);
            throw new AccountNotFoundException("Aucun compte pour cet email");
        }
        AuthAccount account = maybeAccount.get();

        // Toujours execute, meme si un ralentissement est actif : le cout (hachage BCrypt)
        // doit rester identique dans tous les cas ou l'email existe, pour ne jamais laisser
        // un ecart de latence reveler l'etat du ralentissement.
        boolean passwordMatches = passwordEncoder.matches(rawPassword, account.getPasswordHash());

        if (passwordMatches) {
            // REGLE CRITIQUE (R21-D.5) : acceptation INCONDITIONNELLE - jamais de lecture
            // ni de condition sur nextAttemptAllowedAt ici. C'est la propriete qui elimine
            // le deni de service par verrouillage (D4-01) : aucune sequence de mauvais mots
            // de passe, envoyee par quiconque, ne peut empecher le titulaire legitime de se
            // connecter avec son vrai mot de passe, a tout moment.
            //
            // Correction concurrence (verification post-implementation R21-D.5) : remise a
            // zero deleguee a un UPDATE atomique et scoping-minimal cote base (voir
            // AuthAccountRepository.resetFailedAttempts) plutot qu'a une mutation de
            // l'entite geree - evite un UPDATE implicite Hibernate portant sur TOUTES les
            // colonnes de l'entite (email/password_hash/status compris, non concernees par
            // ce reset). Inconditionnelle en temps (pas de WHERE sur locked_until), pour
            // que le mot de passe correct reste toujours accepte immediatement.
            authAccountRepository.resetFailedAttempts(account.getId());

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
            initializeRolesAndPermissions(account);

            return account;
        }

        // Mot de passe incorrect : enregistrement du ralentissement delegue a un UNIQUE
        // UPDATE atomique et conditionnel cote base (voir
        // AuthAccountRepository.registerFailedAttempt) - aucune lecture Java intermediaire
        // du compteur, donc aucun lost update possible sous concurrence (deux executions
        // concurrentes sur le meme compte sont serialisees par le verrou de ligne pris par
        // l'UPDATE lui-meme). Le WHERE de cette requete exclut deja les tentatives arrivant
        // pendant un delai actif (aucune prolongation) : la valeur de retour n'a pas besoin
        // d'etre interpretee ici, la reponse est identique dans tous les cas (generique).
        Instant now = clock.instant();
        authAccountRepository.registerFailedAttempt(account.getId(), now, now.minus(failureWindow),
                now.plus(DELAY_AT_THREE_FAILURES), now.plus(DELAY_AT_FOUR_FAILURES),
                now.plus(DELAY_AT_FIVE_FAILURES), now.plus(DELAY_AT_SIX_OR_MORE_FAILURES));
        throw new InvalidCredentialsException("Mot de passe incorrect");
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
