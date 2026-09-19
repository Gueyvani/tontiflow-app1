package com.tontiflow.application.service;

import com.tontiflow.UserContext;
import com.tontiflow.application.exception.AccountDisabledException;
import com.tontiflow.application.exception.AccountLockedException;
import com.tontiflow.application.exception.AccountNotFoundException;
import com.tontiflow.application.exception.AccountNotFoundInAdminException;
import com.tontiflow.application.exception.InvalidAccountStatusTransitionException;
import com.tontiflow.application.exception.InvalidCredentialsException;
import com.tontiflow.application.exception.RoleAlreadyAssignedException;
import com.tontiflow.application.exception.RoleNotAssignedException;
import com.tontiflow.application.exception.RoleNotFoundException;
import com.tontiflow.domain.enums.AccountStatus;
import com.tontiflow.domain.model.AccountStatusChange;
import com.tontiflow.domain.model.AuthAccount;
import com.tontiflow.domain.model.Permission;
import com.tontiflow.domain.model.RefreshToken;
import com.tontiflow.domain.model.Role;
import com.tontiflow.infrastructure.repository.AccountStatusChangeRepository;
import com.tontiflow.infrastructure.repository.AuthAccountRepository;
import com.tontiflow.infrastructure.repository.RoleRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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

    /**
     * Matrice des transitions administratives autorisées (décision R21-RD,
     * D2), indexée par statut <b>cible</b> → ensemble des statuts
     * <b>source</b> depuis lesquels cette cible est atteignable. N'inclut
     * jamais une cible identique à une source (le cas « statut déjà
     * appliqué » est l'idempotence de D3, traitée séparément, avant toute
     * consultation de cette matrice). Les 5 transitions de D2 sont
     * exactement représentées : ACTIVE→LOCKED, ACTIVE→DISABLED,
     * LOCKED→ACTIVE, LOCKED→DISABLED, DISABLED→ACTIVE.
     * {@code DISABLED -> LOCKED} est délibérément absente (interdite,
     * D2) : {@code DISABLED} n'apparaît pas dans l'ensemble des sources
     * autorisées pour la cible {@code LOCKED} ci-dessous.
     */
    private static final Map<AccountStatus, Set<AccountStatus>> ALLOWED_SOURCE_STATUSES_BY_TARGET = Map.of(
            AccountStatus.LOCKED, Set.of(AccountStatus.ACTIVE),
            AccountStatus.DISABLED, Set.of(AccountStatus.ACTIVE, AccountStatus.LOCKED),
            AccountStatus.ACTIVE, Set.of(AccountStatus.LOCKED, AccountStatus.DISABLED)
    );

    private final AuthAccountRepository authAccountRepository;
    private final RoleRepository roleRepository;
    private final RefreshTokenService refreshTokenService;
    private final AccountStatusChangeRepository accountStatusChangeRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final Duration failureWindow;
    private final EntityManager entityManager;

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
                               RefreshTokenService refreshTokenService,
                               AccountStatusChangeRepository accountStatusChangeRepository,
                               PasswordEncoder passwordEncoder, Clock clock,
                               @Value("${account-lockout.failure-window:15m}") String failureWindow,
                               EntityManager entityManager) {
        this.authAccountRepository = authAccountRepository;
        this.roleRepository = roleRepository;
        this.refreshTokenService = refreshTokenService;
        this.accountStatusChangeRepository = accountStatusChangeRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        // DurationStyle.detectAndParse comprend le format simplifie ("15m", "15s", ...)
        // deja utilise pour jwt.access-token-ttl / refresh-token.ttl, garanti sans
        // ambiguite de conversion @Value (meme motif que RefreshTokenService).
        this.failureWindow = DurationStyle.detectAndParse(failureWindow);
        this.dummyPasswordHash = passwordEncoder.encode(DUMMY_PASSWORD_FOR_TIMING);
        this.entityManager = entityManager;
    }

    /**
     * Crée un nouveau compte d'authentification, statut initial {@link AccountStatus#ACTIVE}.
     *
     * <p>Le mot de passe fourni en clair n'est jamais persisté : seul son
     * hash BCrypt ({@link PasswordEncoder}) est stocké dans
     * {@code AuthAccount.passwordHash}.</p>
     *
     * <p><strong>Absence de signal d'existence (décision R21-D.9, constat
     * D4-05/R21-D.4, Option C)</strong> : contrairement au comportement
     * antérieur, cette méthode ne lève <b>plus</b> d'exception distincte
     * lorsque l'email est déjà utilisé — elle retourne silencieusement,
     * exactement comme après une création réussie. C'est
     * {@link com.tontiflow.interfaces.rest.AuthController#register} qui
     * traduit ce comportement en une réponse HTTP strictement identique dans
     * les deux cas, empêchant toute énumération de comptes via {@code
     * /api/v1/auth/register}. Le contrôle {@code existsByEmail} reste un
     * simple raccourci de performance (évite un hachage BCrypt et une
     * tentative d'écriture vouée à l'échec dans le cas non concurrent le
     * plus courant) — il n'est <b>pas</b> la protection réelle contre les
     * doublons, qui reste entièrement portée par la contrainte
     * {@code uk_auth_account_email} (voir ci-dessous, gestion de la
     * course).</p>
     *
     * <p><strong>Course concurrente</strong> : si deux requêtes concurrentes
     * passent toutes deux {@code existsByEmail() == false} avant qu'aucune
     * n'ait validé son écriture, la seconde à atteindre la validation de
     * cette transaction échoue sur la contrainte unique {@code
     * uk_auth_account_email} — {@link org.springframework.dao.DataIntegrityViolationException}.
     * Cette méthode ne capture <b>volontairement pas</b> cette exception en
     * interne (elle laisse le proxy {@code @Transactional} de Spring
     * effectuer un rollback complet et protocolairement correct de cette
     * transaction <b>avant</b> de la propager à l'appelant) : un
     * {@code catch} local autour de l'écriture, sans annuler explicitement la
     * transaction, risquerait de la laisser dans un état inutilisable
     * côté moteur (une transaction PostgreSQL avortée par une violation de
     * contrainte le reste jusqu'à un {@code ROLLBACK} explicite, qu'une
     * simple capture Java ne déclenche pas). {@link
     * com.tontiflow.interfaces.rest.AuthController#register} capture cette
     * exception <b>après</b> ce rollback complet (la transaction est déjà
     * intégralement close à ce point) et la traite exactement comme le cas
     * « email déjà pris ».</p>
     *
     * @param email       email du nouveau compte
     * @param rawPassword mot de passe en clair, haché avant persistance si le compte est créé
     */
    @Transactional
    public void createAccount(String email, String rawPassword) {
        if (authAccountRepository.existsByEmail(email)) {
            return;
        }

        AuthAccount account = new AuthAccount();
        account.setEmail(email);
        account.setPasswordHash(passwordEncoder.encode(rawPassword));
        account.setStatus(AccountStatus.ACTIVE);

        authAccountRepository.save(account);
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
     * Recherche un compte par son identifiant, en vérifiant son statut.
     *
     * <p><strong>Vérification du statut au renouvellement (décision R21-RD,
     * D6)</strong> : cette méthode n'a, dans tout le code source, qu'un seul
     * appelant réel — {@code AuthController#refresh} — c'est pourquoi la
     * vérification de statut y a été ajoutée directement (même exceptions,
     * même mapping HTTP 423/403 que {@link #authenticate}), plutôt que dans
     * une méthode séparée : un compte {@code LOCKED}/{@code DISABLED} ne
     * doit plus pouvoir obtenir de nouvel Access Token via {@code /refresh}.
     * Ce contrôle est un <b>second</b> mécanisme de défense en profondeur,
     * distinct de la révocation de toutes les familles de refresh token
     * opérée par {@link #changeAccountStatus} au moment de l'action
     * administrative : il ferme spécifiquement la fenêtre de course où une
     * nouvelle famille serait émise (via {@code /login}) juste après cette
     * révocation, avant qu'un changement de statut ultérieur n'ait pu en
     * tenir compte — un token présenté dans ce cas précis échouerait
     * sinon la vérification de réutilisation de {@code RefreshTokenService#rotate}
     * (la famille n'ayant jamais été révoquée) et atteindrait cette méthode
     * sans être bloqué autrement. <b>Ne modifie jamais {@code
     * AuthAccountRepository.assignRole}/{@code removeRole}</b>, qui accèdent
     * au repository directement, sans passer par cette méthode.</p>
     *
     * @param id identifiant du compte
     * @return le compte correspondant, si son statut est {@link AccountStatus#ACTIVE}
     * @throws AccountNotFoundInAdminException si aucun compte ne correspond à cet identifiant
     * @throws AccountLockedException          si le compte est {@link AccountStatus#LOCKED}
     * @throws AccountDisabledException        si le compte est {@link AccountStatus#DISABLED}
     */
    @Transactional(readOnly = true)
    public AuthAccount findById(UUID id) {
        AuthAccount account = authAccountRepository.findById(id)
                .orElseThrow(() -> new AccountNotFoundInAdminException("Compte introuvable"));

        switch (account.getStatus()) {
            case LOCKED -> throw new AccountLockedException("Compte verrouille");
            case DISABLED -> throw new AccountDisabledException("Compte desactive");
            case ACTIVE -> {
                // Renouvellement autorise.
            }
        }

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
    // AccountLockedException/AccountDisabledException (decision TICKET-1, audit post-R21-RD-FU,
    // constat A1) : meme necessite symetrique sur le CHEMIN MOT DE PASSE CORRECT - sans ceci,
    // resetFailedAttempts() (ligne ci-dessous) serait lui aussi annule par le rollback par
    // defaut au moment ou l'un de ces deux statuts bloquants est detecte juste apres, alors
    // que le mot de passe presente etait pourtant correct.
    @Transactional(noRollbackFor = {InvalidCredentialsException.class, AccountLockedException.class, AccountDisabledException.class})
    public AuthAccount authenticate(String email, String rawPassword) {
        return authenticateInternal(email, rawPassword);
    }

    /**
     * Corps réel de {@link #authenticate}, extrait en méthode privée
     * <b>non</b> {@code @Transactional} (décision R21-RD-FU) : {@link #login}
     * a besoin d'exécuter cette même logique <b>dans sa propre transaction</b>
     * (pour y ajouter ensuite, dans la même transaction, le verrou de ligne et
     * l'émission du Refresh Token — voir {@link #login}). Un appel direct de
     * {@code login()} vers la méthode publique {@code authenticate()} (les
     * deux dans la même classe) serait une <b>auto-invocation Spring</b> :
     * l'appel `this.authenticate(...)` ne passerait pas par le proxy AOP, et
     * l'annotation {@code @Transactional(noRollbackFor = ...)} de {@code
     * authenticate()} serait silencieusement ignorée — seule celle de la
     * méthode proxee appelée <b>depuis l'extérieur</b> ({@code login()} elle-même)
     * s'appliquerait alors, ce qui aurait annulé par erreur l'écriture de
     * {@link AuthAccountRepository#registerFailedAttempt} en cas de mot de
     * passe incorrect. En extrayant la logique dans cette méthode privée sans
     * annotation propre, {@code authenticate()} et {@code login()} restent
     * chacune un point d'entrée transactionnel unique et correctement configuré,
     * sans dépendre d'un appel implicite au proxy de l'autre.
     */
    private AuthAccount authenticateInternal(String email, String rawPassword) {
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

    /**
     * Résultat de {@link #login} : le compte authentifié et la nouvelle
     * famille de Refresh Token émise, tous deux produits par la <b>même</b>
     * transaction.
     */
    public record LoginResult(AuthAccount account, RefreshToken refreshToken) {
    }

    /**
     * Authentifie un compte <b>et</b> émet sa nouvelle famille de Refresh
     * Token, dans une <b>unique</b> transaction (décision R21-RD-FU, corrige
     * la fenêtre de course documentée à la clôture de R21-RD : {@code
     * AuthController#login} appelait auparavant {@link #authenticate} puis
     * {@code RefreshTokenService#issue} dans deux transactions séparées, sans
     * aucune revérification du statut entre les deux — un changement
     * administratif de statut pouvant s'intercaler entre les deux et révoquer
     * toutes les familles existantes <b>avant</b> que celle-ci n'existe
     * encore, la laissant orpheline, non révoquée).
     *
     * <p><strong>Verrou de ligne et relecture forcée</strong> : après
     * authentification réussie ({@link #authenticateInternal}, dont la
     * propre vérification de statut reste une première passe non-autoritaire
     * — voir ci-dessous), cette méthode acquiert explicitement un verrou
     * {@link LockModeType#PESSIMISTIC_WRITE} sur la ligne {@code auth_account}
     * concernée, <b>puis</b> force une relecture réelle ({@link
     * EntityManager#refresh}) avant de trancher — jamais en se fiant au champ
     * Java {@code account.getStatus()} tel que lu par {@code
     * authenticateInternal} <b>avant</b> l'acquisition de ce verrou (même
     * piège de péremption que celui déjà corrigé dans {@link
     * #changeAccountStatus} : un verrou seul n'actualise pas nécessairement,
     * selon le fournisseur JPA, les champs d'une entité déjà gérée dans le
     * contexte de persistance — {@code refresh()} après {@code lock()} est la
     * seule garantie non ambiguë).</p>
     *
     * <p><strong>Sérialisation réelle avec {@link #changeAccountStatus}</strong> :
     * {@link AuthAccountRepository#transitionStatusIfAllowed} est un {@code
     * UPDATE} qui prend lui-même un verrou exclusif sur cette même ligne le
     * temps de sa transaction. Les deux ordres de concurrence possibles sont
     * donc tous deux sûrs : si <b>ce</b> verrou est acquis en premier, la
     * nouvelle famille de Refresh Token est déjà committée avant que la
     * révocation administrative ne s'exécute, et sera donc rattrapée par
     * elle ; si le verrou de {@code changeAccountStatus} est acquis en
     * premier, cette méthode bloque jusqu'à son commit, puis relit
     * nécessairement le statut à jour (LOCKED/DISABLED) et rejette avant
     * toute émission. Aucune fenêtre ne subsiste entre la vérification et
     * l'émission — contrairement à l'ancien enchaînement en deux
     * transactions séparées.</p>
     *
     * <p><strong>Auto-invocation évitée</strong> : voir javadoc de {@link
     * #authenticateInternal}.</p>
     *
     * <p><strong>Limite de preuve</strong> : le verrouillage de ligne
     * (`SELECT ... FOR UPDATE`) est une primitive SQL standard, supportée
     * aussi bien par H2 (tests) que PostgreSQL (production) — mais, comme
     * pour tous les tests de concurrence de ce dépôt, l'exécution réelle
     * n'est prouvée que sous H2 ; la garantie sous PostgreSQL réel reste une
     * inférence fondée sur la sémantique standard READ COMMITTED partagée
     * par les deux moteurs, non une preuve d'exécution directe.</p>
     *
     * @param email       email du compte
     * @param rawPassword mot de passe en clair fourni pour la tentative
     * @return le compte authentifié et sa nouvelle famille de Refresh Token
     * @throws AccountNotFoundException    si aucun compte ne correspond à l'email
     * @throws InvalidCredentialsException si le mot de passe ne correspond pas
     * @throws AccountLockedException      si le compte est {@link AccountStatus#LOCKED}
     *                                      (détecté par {@code authenticateInternal} ou,
     *                                      sous course, par la relecture verrouillée ci-dessous)
     * @throws AccountDisabledException    si le compte est {@link AccountStatus#DISABLED}
     *                                      (même remarque)
     */
    // noRollbackFor (decision TICKET-1, audit post-R21-RD-FU, constat A1) : meme necessite
    // qu'authenticate() ci-dessus - authenticateInternal() (appelee juste en dessous) execute
    // resetFailedAttempts() sur le chemin mot de passe correct AVANT que le statut bloquant ne
    // soit detecte (ici ou, sous course, apres le verrou/relecture plus bas) ; sans ceci, cette
    // ecriture deja validee cote SQL serait annulee par le rollback par defaut de Spring.
    @Transactional(noRollbackFor = {InvalidCredentialsException.class, AccountLockedException.class, AccountDisabledException.class})
    public LoginResult login(String email, String rawPassword) {
        AuthAccount account = authenticateInternal(email, rawPassword);

        entityManager.lock(account, LockModeType.PESSIMISTIC_WRITE);
        // entityManager.refresh() recharge TOUTES les colonnes/associations depuis la base,
        // y compris les collections LAZY roles/permissions deja initialisees par
        // authenticateInternal() ci-dessus : il les remet a l'etat de proxy NON initialise
        // (nouvelle instance de collection persistante Hibernate). Sans la reinitialisation
        // ci-dessous, toUserContext(...) - appele plus tard hors de cette transaction par
        // AuthController - leverait LazyInitializationException. Reinitialiser apres refresh
        // est donc necessaire, pas redondant, malgre l'appel deja fait dans authenticateInternal.
        entityManager.refresh(account);

        switch (account.getStatus()) {
            case LOCKED -> throw new AccountLockedException("Compte verrouille");
            case DISABLED -> throw new AccountDisabledException("Compte desactive");
            case ACTIVE -> {
                // Emission autorisee.
            }
        }

        initializeRolesAndPermissions(account);

        RefreshToken refreshToken = refreshTokenService.issue(account.getId());
        return new LoginResult(account, refreshToken);
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

    /**
     * Applique une transition administrative du statut d'un compte
     * (décision R21-RD) : {@code /api/v1/admin/accounts/{accountId}/status}.
     *
     * <p><strong>Idempotence (D3)</strong> : si le compte possède déjà le
     * statut demandé, retour immédiat sans aucune écriture (ni changement de
     * statut, ni révocation, ni événement d'audit) — y compris sous
     * concurrence, voir ci-dessous.</p>
     *
     * <p><strong>Concurrence (D2/D3)</strong> : la transition elle-même est
     * appliquée par {@link AuthAccountRepository#transitionStatusIfAllowed},
     * un <b>unique</b> {@code UPDATE} conditionnel encodant directement la
     * matrice {@link #ALLOWED_SOURCE_STATUSES_BY_TARGET} dans sa clause
     * {@code WHERE} — aucune transition interdite ne peut donc jamais être
     * appliquée, même sous accès concurrent. Si cet {@code UPDATE} n'affecte
     * aucune ligne, l'état réel est relu via {@link EntityManager#refresh}
     * (jamais un second {@code findById}, voir ci-dessous) : soit un autre
     * thread a entre-temps appliqué exactement cette même transition (traité
     * comme un succès idempotent, sans nouvel audit ni nouvelle révocation),
     * soit la transition demandée n'est plus valide depuis ce nouvel état réel
     * (exception).</p>
     *
     * <p><strong>Piège du cache de premier niveau (L1) Hibernate</strong> :
     * {@code account} est déjà géré par le contexte de persistance de cette
     * transaction depuis le {@code findById} initial ci-dessous. Un second
     * appel à {@code authAccountRepository.findById(accountId)} après un
     * {@code UPDATE} natif n'émettrait <b>aucune</b> requête SQL — Hibernate
     * renverrait directement l'instance déjà en cache (donc l'état
     * <em>avant</em> l'{@code UPDATE}), quel que soit l'état réellement commité
     * entre-temps par un autre thread. C'est pourquoi la relecture ci-dessous
     * utilise {@link EntityManager#refresh}, qui force une véritable requête
     * SQL et écrase l'état de l'entité déjà gérée.</p>
     *
     * <p><strong>Atomicité (D7)</strong> : {@code AuthAccountRepository},
     * {@code RefreshTokenRepository} et {@code AccountStatusChangeRepository}
     * partagent tous la même base {@code authentication_db} — cette méthode
     * et {@link RefreshTokenService#revokeAllForAccount} (propagation
     * {@code REQUIRED} par défaut) s'exécutent donc dans une <b>unique</b>
     * transaction réelle : le changement de statut, la révocation de toutes
     * les familles de refresh token actives, et l'écriture de l'événement
     * d'audit sont validés ou annulés ensemble, sans simulation ni
     * approximation.</p>
     *
     * <p><strong>Access Tokens déjà émis (D6)</strong> : cette méthode ne
     * modifie ni ne révoque jamais un Access Token JWT déjà délivré — {@link
     * com.tontiflow.infrastructure.security.jwt.AccessTokenService#validate}
     * reste purement cryptographique (signature + expiration), sans accès à
     * la base. Un Access Token émis avant cette transition reste donc valide
     * jusqu'à son expiration naturelle ({@code jwt.access-token-ttl},
     * 15 minutes par défaut) — cette méthode ne prétend à aucune
     * invalidation immédiate de ces tokens.</p>
     *
     * @param accountId      compte ciblé
     * @param targetStatus   statut demandé
     * @param reason         motif de la transition (jamais persisté ailleurs que dans
     *                       l'événement d'audit, jamais journalisé, jamais renvoyé)
     * @param actorAccountId identifiant de l'administrateur acteur, dérivé du contexte
     *                       d'authentification vérifié (JWT) — jamais fourni par le client
     * @return le compte ciblé — dans le cas d'une transition réellement appliquée par
     *         cet appel (chemin {@code updated != 0}), l'entité gérée n'est <b>pas</b>
     *         rafraîchie après l'{@code UPDATE} natif : ne pas se fier à
     *         {@link AuthAccount#getStatus()} sur la valeur retournée dans ce cas précis,
     *         l'appelant connaît déjà le nouveau statut ({@code targetStatus} lui-même).
     *         Dans le cas idempotent détecté après course ({@code updated == 0}), l'entité
     *         retournée a été explicitement rafraîchie ({@link EntityManager#refresh}) et
     *         son statut est donc fiable. {@link AuthAccount#getId()}/{@link AuthAccount#getEmail()}
     *         sont, dans tous les cas, garantis à jour (jamais modifiés par cette méthode).
     * @throws AccountNotFoundInAdminException          si {@code accountId} n'existe pas
     * @throws InvalidAccountStatusTransitionException  si la transition n'est pas autorisée
     *                                                   depuis le statut actuel réel du compte
     */
    @Transactional
    public AuthAccount changeAccountStatus(UUID accountId, AccountStatus targetStatus, String reason, UUID actorAccountId) {
        AuthAccount account = authAccountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundInAdminException("Compte introuvable"));

        AccountStatus currentStatus = account.getStatus();
        if (currentStatus == targetStatus) {
            // Idempotence (D3) : aucune ecriture, aucun audit, aucune revocation.
            return account;
        }

        Set<AccountStatus> allowedSources = ALLOWED_SOURCE_STATUSES_BY_TARGET.get(targetStatus);
        if (!allowedSources.contains(currentStatus)) {
            throw new InvalidAccountStatusTransitionException(
                    "Transition " + currentStatus + " -> " + targetStatus + " non autorisee");
        }

        List<String> allowedSourceNames = allowedSources.stream().map(Enum::name).toList();
        int updated = authAccountRepository.transitionStatusIfAllowed(accountId, targetStatus.name(), allowedSourceNames);

        if (updated == 0) {
            // Course perdue entre la lecture ci-dessus et l'UPDATE : un autre thread a
            // deja modifie ce compte entre-temps. On relit l'etat reel pour determiner
            // si la transition demandee est desormais idempotente (un autre thread a
            // applique exactement la meme transition) ou toujours invalide.
            //
            // entityManager.refresh (et non un second findById) : account est deja gere
            // par le contexte de persistance de cette transaction depuis le findById
            // initial ci-dessus - un second findById renverrait l'instance DEJA EN CACHE
            // (donc l'etat AVANT l'UPDATE natif, qui ne passe jamais par le cache L1),
            // quel que soit l'etat reellement commite entre-temps par le thread gagnant.
            // refresh() force une veritable requete SQL et ecrase l'etat en place.
            entityManager.refresh(account);
            if (account.getStatus() == targetStatus) {
                return account;
            }
            throw new InvalidAccountStatusTransitionException(
                    "Transition " + account.getStatus() + " -> " + targetStatus + " non autorisee");
        }

        // Meme transaction (voir javadoc ci-dessus) : revocation de toutes les familles
        // actives du compte, puis ecriture de l'evenement d'audit.
        refreshTokenService.revokeAllForAccount(accountId);

        AccountStatusChange event = new AccountStatusChange(
                accountId, actorAccountId, currentStatus, targetStatus, reason, clock.instant());
        accountStatusChangeRepository.save(event);

        return account;
    }
}
