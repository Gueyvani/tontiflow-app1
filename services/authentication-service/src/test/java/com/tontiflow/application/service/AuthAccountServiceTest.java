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
import com.tontiflow.domain.model.Role;
import com.tontiflow.infrastructure.repository.AccountStatusChangeRepository;
import com.tontiflow.infrastructure.repository.AuthAccountRepository;
import com.tontiflow.infrastructure.repository.RoleRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires de {@link AuthAccountService}.
 *
 * <p>Le {@link AuthAccountRepository} est simulé (Mockito) ; un
 * {@link BCryptPasswordEncoder} réel est utilisé pour vérifier le
 * comportement effectif du hachage, pas une simulation. Toutes les valeurs
 * de mot de passe utilisées ici sont des valeurs de test, sans rapport avec
 * un mot de passe réel.</p>
 */
@ExtendWith(MockitoExtension.class)
class AuthAccountServiceTest {

    private static final String TEST_PASSWORD = "S3cur3-Test-Passw0rd!";
    private static final Instant FIXED_NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Mock
    private AuthAccountRepository authAccountRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private RefreshTokenService refreshTokenService;

    @Mock
    private AccountStatusChangeRepository accountStatusChangeRepository;

    @Mock
    private EntityManager entityManager;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private AuthAccountService authAccountService;

    @BeforeEach
    void setUp() {
        authAccountService = newService(Clock.fixed(FIXED_NOW, ZoneOffset.UTC), "15m");
    }

    private AuthAccountService newService(Clock clock, String failureWindow) {
        return new AuthAccountService(authAccountRepository, roleRepository, refreshTokenService,
                accountStatusChangeRepository, passwordEncoder, clock, failureWindow, entityManager);
    }

    /** Variante de {@link #newService} avec un encodeur injectable (décision R21-D.8, D4-04) — permet un espionnage Mockito du {@link PasswordEncoder} réel sans affecter {@link #authAccountService}. */
    private AuthAccountService newServiceWithEncoder(PasswordEncoder encoder) {
        return new AuthAccountService(authAccountRepository, roleRepository, refreshTokenService,
                accountStatusChangeRepository, encoder, Clock.fixed(FIXED_NOW, ZoneOffset.UTC), "15m", entityManager);
    }

    @Test
    void createAccount_withAvailableEmail_createsActiveAccountWithHashedPassword() {
        when(authAccountRepository.existsByEmail("new@tontiflow.test")).thenReturn(false);
        ArgumentCaptor<AuthAccount> savedCaptor = ArgumentCaptor.forClass(AuthAccount.class);
        when(authAccountRepository.save(savedCaptor.capture())).thenAnswer(invocation -> invocation.getArgument(0));

        authAccountService.createAccount("new@tontiflow.test", TEST_PASSWORD);

        AuthAccount created = savedCaptor.getValue();
        assertThat(created.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(created.getPasswordHash()).isNotNull().isNotEqualTo(TEST_PASSWORD);
        assertThat(passwordEncoder.matches(TEST_PASSWORD, created.getPasswordHash())).isTrue();
    }

    // ------------------------------------------------------------------
    // Décision R21-D.9 (constat D4-05/R21-D.4, Option C) : absence de signal
    // d'existence. createAccount() ne leve plus DuplicateEmailException -
    // elle retourne silencieusement, exactement comme apres une creation
    // reussie, pour empecher toute enumeration de comptes via /register.
    // ------------------------------------------------------------------

    @Test
    void createAccount_withExistingEmail_returnsSilently_withoutCreatingOrThrowing() {
        when(authAccountRepository.existsByEmail("duplicate@tontiflow.test")).thenReturn(true);

        assertThatCode(() -> authAccountService.createAccount("duplicate@tontiflow.test", TEST_PASSWORD))
                .doesNotThrowAnyException();

        // Aucune ecriture ne doit etre tentee - le raccourci existsByEmail() evite un
        // hachage BCrypt et un save() voues a l'echec dans ce cas non concurrent.
        verify(authAccountRepository, never()).save(any());
    }

    @Test
    void createAccount_whenSaveViolatesUniqueConstraint_propagatesDataIntegrityViolationException() {
        // Simule la course concurrente : existsByEmail() a repondu false (aucun compte
        // trouve a cet instant), mais save() echoue neanmoins sur la contrainte unique
        // uk_auth_account_email (un autre thread a cree ce compte entre-temps). Cette
        // methode ne doit PAS capturer l'exception elle-meme (voir javadoc de
        // createAccount()) - c'est AuthController.register() qui la traite, APRES que
        // le proxy @Transactional de Spring a deja execute un rollback complet.
        when(authAccountRepository.existsByEmail("race@tontiflow.test")).thenReturn(false);
        when(authAccountRepository.save(any(AuthAccount.class)))
                .thenThrow(new DataIntegrityViolationException("uk_auth_account_email"));

        assertThatThrownBy(() -> authAccountService.createAccount("race@tontiflow.test", TEST_PASSWORD))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findByEmail_withExistingAccount_returnsAccount() {
        AuthAccount account = activeAccount("found@tontiflow.test", TEST_PASSWORD);
        when(authAccountRepository.findByEmail("found@tontiflow.test")).thenReturn(Optional.of(account));

        Optional<AuthAccount> result = authAccountService.findByEmail("found@tontiflow.test");

        assertThat(result).contains(account);
    }

    @Test
    void authenticate_withUnknownEmail_throwsAccountNotFoundException() {
        when(authAccountRepository.findByEmail("unknown@tontiflow.test")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authAccountService.authenticate("unknown@tontiflow.test", TEST_PASSWORD))
                .isInstanceOf(AccountNotFoundException.class);
    }

    // ------------------------------------------------------------------
    // Décision R21-D.8 (constat D4-04/R21-D.4) : oracle de timing sur email
    // inconnu. Le chemin email-inconnu doit désormais exécuter, lui aussi, un
    // vrai appel BCrypt (contre un hash factice, jamais un vrai compte) avant
    // de lever AccountNotFoundException — sans jamais créer d'état R21-D.5.
    // Le PasswordEncoder de la classe est un BCryptPasswordEncoder réel (pas
    // un mock) : on l'espionne (Mockito.spy) au lieu de le simuler, pour
    // vérifier une invocation réelle tout en conservant son comportement
    // cryptographique effectif — cohérent avec le choix déjà fait pour le
    // reste de cette classe de test (voir javadoc de classe).
    // ------------------------------------------------------------------

    @Test
    void authenticate_withUnknownEmail_invokesDummyBCryptMatch_andCreatesNoR21D5State() {
        PasswordEncoder spyEncoder = spy(new BCryptPasswordEncoder());
        AuthAccountService serviceWithSpy = newServiceWithEncoder(spyEncoder);
        when(authAccountRepository.findByEmail("unknown-dummy@tontiflow.test")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> serviceWithSpy.authenticate("unknown-dummy@tontiflow.test", TEST_PASSWORD))
                .isInstanceOf(AccountNotFoundException.class);

        // Un appel BCrypt reel a bien lieu meme pour un email inconnu - c'est le mecanisme
        // qui ferme l'oracle de timing (le resultat de matches() est ignore par construction).
        verify(spyEncoder).matches(eq(TEST_PASSWORD), anyString());
        // Aucun etat R21-D.5 (compteur/delai de ralentissement) ne doit jamais etre cree
        // pour un email inexistant : ce chemin n'accede a AuthAccountRepository qu'en
        // lecture (findByEmail), jamais en ecriture.
        verify(authAccountRepository, never()).registerFailedAttempt(any(), any(), any(), any(), any(), any(), any());
        verify(authAccountRepository, never()).resetFailedAttempts(any());
    }

    @Test
    void authenticate_unknownEmailAndKnownEmail_compareAgainstDifferentBCryptHashes() {
        // Preuve que le chemin email-inconnu compare bien contre le hash FACTICE (jamais
        // celui d'un compte reel) alors que le chemin email-connu compare bien contre le
        // VRAI hash du compte - les deux executent un round BCrypt comparable, mais jamais
        // contre la meme valeur.
        PasswordEncoder spyEncoder = spy(new BCryptPasswordEncoder());
        AuthAccountService serviceWithSpy = newServiceWithEncoder(spyEncoder);
        AuthAccount knownAccount = activeAccount("hashdiff@tontiflow.test", TEST_PASSWORD);
        when(authAccountRepository.findByEmail("unknown-hashdiff@tontiflow.test")).thenReturn(Optional.empty());
        when(authAccountRepository.findByEmail("hashdiff@tontiflow.test")).thenReturn(Optional.of(knownAccount));

        assertThatThrownBy(() -> serviceWithSpy.authenticate("unknown-hashdiff@tontiflow.test", "peu-importe-1234"))
                .isInstanceOf(AccountNotFoundException.class);
        assertThatThrownBy(() -> serviceWithSpy.authenticate("hashdiff@tontiflow.test", "mauvais-mot-de-passe"))
                .isInstanceOf(InvalidCredentialsException.class);

        ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);
        verify(spyEncoder, times(2)).matches(anyString(), hashCaptor.capture());
        List<String> comparedHashes = hashCaptor.getAllValues();

        assertThat(comparedHashes.get(0)).isNotEqualTo(knownAccount.getPasswordHash()); // email inconnu -> hash factice
        assertThat(comparedHashes.get(1)).isEqualTo(knownAccount.getPasswordHash());    // email connu -> vrai hash
    }

    @Test
    void authenticate_withWrongPassword_throwsInvalidCredentialsException() {
        AuthAccount account = activeAccount("wrongpwd@tontiflow.test", TEST_PASSWORD);
        when(authAccountRepository.findByEmail("wrongpwd@tontiflow.test")).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> authAccountService.authenticate("wrongpwd@tontiflow.test", "mot-de-passe-incorrect"))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void authenticate_withLockedAccount_throwsAccountLockedException() {
        AuthAccount account = accountWithStatus("locked@tontiflow.test", TEST_PASSWORD, AccountStatus.LOCKED);
        when(authAccountRepository.findByEmail("locked@tontiflow.test")).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> authAccountService.authenticate("locked@tontiflow.test", TEST_PASSWORD))
                .isInstanceOf(AccountLockedException.class);
    }

    @Test
    void authenticate_withDisabledAccount_throwsAccountDisabledException() {
        AuthAccount account = accountWithStatus("disabled@tontiflow.test", TEST_PASSWORD, AccountStatus.DISABLED);
        when(authAccountRepository.findByEmail("disabled@tontiflow.test")).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> authAccountService.authenticate("disabled@tontiflow.test", TEST_PASSWORD))
                .isInstanceOf(AccountDisabledException.class);
    }

    @Test
    void authenticate_withActiveAccountAndCorrectPassword_returnsAccount() {
        AuthAccount account = activeAccount("active@tontiflow.test", TEST_PASSWORD);
        when(authAccountRepository.findByEmail("active@tontiflow.test")).thenReturn(Optional.of(account));

        AuthAccount result = authAccountService.authenticate("active@tontiflow.test", TEST_PASSWORD);

        assertThat(result).isSameAs(account);
    }

    // ------------------------------------------------------------------
    // Décision R21-RD-FU : authentification et émission du Refresh Token
    // dans une unique transaction (corrige la fenêtre de course avec
    // changeAccountStatus documentée à la clôture de R21-RD). Le verrou de
    // ligne lui-même (EntityManager#lock) n'est pas reproductible avec un
    // EntityManager simulé (Mockito ne peut pas exécuter de SQL) - la preuve
    // SQL réelle est apportée séparément par un test de concurrence dédié
    // (threads réels, H2). Ces tests unitaires vérifient la DÉLÉGATION
    // correcte : verrou+relecture systématiquement effectués avant toute
    // décision, jamais de statut Java périmé utilisé, refus propre sans
    // émission si le statut relu est bloquant.
    // ------------------------------------------------------------------

    @Test
    void login_withActiveAccount_locksRefreshesAndIssuesRefreshToken() {
        AuthAccount account = activeAccount("login-active@tontiflow.test", TEST_PASSWORD);
        when(authAccountRepository.findByEmail("login-active@tontiflow.test")).thenReturn(Optional.of(account));
        com.tontiflow.domain.model.RefreshToken issued = new com.tontiflow.domain.model.RefreshToken();
        when(refreshTokenService.issue(account.getId())).thenReturn(issued);

        AuthAccountService.LoginResult result = authAccountService.login("login-active@tontiflow.test", TEST_PASSWORD);

        assertThat(result.account()).isSameAs(account);
        assertThat(result.refreshToken()).isSameAs(issued);
        verify(entityManager).lock(account, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        verify(entityManager).refresh(account);
        verify(refreshTokenService).issue(account.getId());
    }

    @Test
    void login_withLockedAccountFromTheStart_throwsWithoutIssuingRefreshToken() {
        AuthAccount account = accountWithStatus("login-locked@tontiflow.test", TEST_PASSWORD, AccountStatus.LOCKED);
        when(authAccountRepository.findByEmail("login-locked@tontiflow.test")).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> authAccountService.login("login-locked@tontiflow.test", TEST_PASSWORD))
                .isInstanceOf(AccountLockedException.class);

        verify(refreshTokenService, never()).issue(any());
    }

    @Test
    void login_withDisabledAccountFromTheStart_throwsWithoutIssuingRefreshToken() {
        AuthAccount account = accountWithStatus("login-disabled@tontiflow.test", TEST_PASSWORD, AccountStatus.DISABLED);
        when(authAccountRepository.findByEmail("login-disabled@tontiflow.test")).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> authAccountService.login("login-disabled@tontiflow.test", TEST_PASSWORD))
                .isInstanceOf(AccountDisabledException.class);

        verify(refreshTokenService, never()).issue(any());
    }

    @Test
    void login_statusBecomesLockedBetweenInitialReadAndLockedRefresh_rejectsWithoutIssuingRefreshToken() {
        // Simule la course fermee par R21-RD-FU : authenticateInternal lit ACTIVE (aucune
        // ecriture concurrente encore visible a cet instant), mais la relecture forcee
        // apres acquisition du verrou (entityManager.refresh) revele LOCKED - exactement
        // ce qu'un vrai changeAccountStatus concurrent aurait commis entre-temps. Le champ
        // Java potentiellement perime lu par authenticateInternal ne doit JAMAIS etre celui
        // qui tranche la decision finale.
        AuthAccount account = activeAccount("login-race@tontiflow.test", TEST_PASSWORD);
        when(authAccountRepository.findByEmail("login-race@tontiflow.test")).thenReturn(Optional.of(account));
        org.mockito.Mockito.doAnswer(invocation -> {
            account.setStatus(AccountStatus.LOCKED); // "commite" par un autre thread pendant le verrou
            return null;
        }).when(entityManager).refresh(account);

        assertThatThrownBy(() -> authAccountService.login("login-race@tontiflow.test", TEST_PASSWORD))
                .isInstanceOf(AccountLockedException.class);

        verify(entityManager).lock(account, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        verify(refreshTokenService, never()).issue(any());
    }

    // ------------------------------------------------------------------
    // Décision R21-D.5 : ralentissement progressif, remplace le verrouillage
    // dur de R21-D.3 (corrige le déni de service par verrouillage, constat
    // D4-01/R21-D.4). Le comptage / la fenêtre / la table de délai sont
    // calculés par un UNIQUE UPDATE atomique et conditionnel côté base
    // (registerFailedAttempt), donc non reproductibles avec un repository
    // simulé (Mockito ne peut pas exécuter de SQL). Ces tests unitaires
    // vérifient uniquement la DÉLÉGATION correcte (arguments transmis) et la
    // RÈGLE CRITIQUE (mot de passe correct toujours accepté, sans condition,
    // sans jamais consulter le ralentissement). L'arithmétique de la table de
    // délai elle-même est prouvée par de vraies requêtes SQL dans
    // AuthAccountRepositoryTest (H2 réel, séquentiel) et par
    // AccountLockoutConcurrencyIntegrationTest (H2 réel, accès concurrent).
    // ------------------------------------------------------------------

    @Test
    void authenticate_withWrongPassword_delegatesToRegisterFailedAttempt_withComputedWindowAndDelayBounds() {
        AuthAccount account = activeAccount("lockout1@tontiflow.test", TEST_PASSWORD);
        when(authAccountRepository.findByEmail("lockout1@tontiflow.test")).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> authAccountService.authenticate("lockout1@tontiflow.test", "mauvais-mot-de-passe"))
                .isInstanceOf(InvalidCredentialsException.class);

        // Verifie que le service calcule et transmet exactement les bornes attendues a
        // l'UPDATE atomique : fenetre glissante de 15 min, et les 4 echeances fixes de la
        // table de delai (2s/5s/10s/30s) - sans jamais lire/ecrire ces champs lui-meme en Java.
        verify(authAccountRepository).registerFailedAttempt(
                account.getId(), FIXED_NOW, FIXED_NOW.minus(Duration.ofMinutes(15)),
                FIXED_NOW.plusSeconds(2), FIXED_NOW.plusSeconds(5), FIXED_NOW.plusSeconds(10), FIXED_NOW.plusSeconds(30));
    }

    @Test
    void authenticate_withCorrectPassword_alwaysSucceeds_regardlessOfPriorFailedAttempts_delegatesResetAtomically() {
        // REGLE CRITIQUE R21-D.5 (preuve directe de la correction D4-01) : meme un compte
        // "profondement" dans le ralentissement (10 echecs prealables simules en memoire,
        // valeur jamais relue ni interrogee par authenticate()) voit son bon mot de passe
        // accepte IMMEDIATEMENT - aucun appel au repository pour verifier/consulter un
        // quelconque etat de ralentissement sur le chemin succes.
        //
        // Correction concurrence (verification post-implementation) : la remise a zero est
        // deleguee a AuthAccountRepository.resetFailedAttempts (UPDATE atomique et
        // scoping-minimal cote base), plus a une mutation de l'entite geree - avec un
        // repository simule, on verifie donc la DELEGATION (l'appel a eu lieu avec le bon
        // identifiant), pas un effet de bord sur l'objet Java (qui reste volontairement
        // perime en memoire apres l'appel, sans consequence - voir authenticate()).
        AuthAccount account = activeAccount("lockout2@tontiflow.test", TEST_PASSWORD);
        account.setFailedAttempts(10);
        account.setNextAttemptAllowedAt(FIXED_NOW.plusSeconds(29)); // ralentissement encore actif
        when(authAccountRepository.findByEmail("lockout2@tontiflow.test")).thenReturn(Optional.of(account));

        AuthAccount result = authAccountService.authenticate("lockout2@tontiflow.test", TEST_PASSWORD);

        assertThat(result).isSameAs(account);
        verify(authAccountRepository).resetFailedAttempts(account.getId());
        // registerFailedAttempt (chemin echec) n'est jamais appele sur le chemin succes.
        verify(authAccountRepository, never()).registerFailedAttempt(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void toUserContext_mapsFieldsAndFlattensRolesAndDeduplicatesPermissions() {
        Permission read = permission("ACCOUNT_READ");
        Permission write = permission("ACCOUNT_WRITE");

        Role admin = role("ADMIN", Set.of(read, write));
        // ACCOUNT_READ est partagee avec ADMIN : doit apparaitre une seule fois apres aplatissement.
        Role member = role("MEMBER", Set.of(read));

        AuthAccount account = activeAccount("context@tontiflow.test", TEST_PASSWORD);
        account.setRoles(Set.of(admin, member));

        UserContext context = authAccountService.toUserContext(account);

        assertThat(context.userId()).isEqualTo(account.getId());
        assertThat(context.username()).isEqualTo(account.getEmail());
        assertThat(context.email()).isEqualTo(account.getEmail());
        assertThat(context.roles()).containsExactlyInAnyOrder("ADMIN", "MEMBER");
        assertThat(context.permissions()).containsExactlyInAnyOrder("ACCOUNT_READ", "ACCOUNT_WRITE");
    }

    @Test
    void assignRole_withExistingAccountAndRole_addsRole() {
        AuthAccount account = accountWithId();
        Role role = roleWithId("ROLE_MEMBER");

        when(authAccountRepository.findById(account.getId())).thenReturn(java.util.Optional.of(account));
        when(roleRepository.findById(role.getId())).thenReturn(java.util.Optional.of(role));

        authAccountService.assignRole(account.getId(), role.getId());

        assertThat(account.getRoles()).contains(role);
    }

    @Test
    void assignRole_withUnknownAccount_throwsAccountNotFoundInAdminException() {
        UUID accountId = UUID.randomUUID();
        when(authAccountRepository.findById(accountId)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> authAccountService.assignRole(accountId, UUID.randomUUID()))
                .isInstanceOf(AccountNotFoundInAdminException.class);
    }

    @Test
    void assignRole_withUnknownRole_throwsRoleNotFoundException() {
        AuthAccount account = accountWithId();
        UUID roleId = UUID.randomUUID();

        when(authAccountRepository.findById(account.getId())).thenReturn(java.util.Optional.of(account));
        when(roleRepository.findById(roleId)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> authAccountService.assignRole(account.getId(), roleId))
                .isInstanceOf(RoleNotFoundException.class);
    }

    @Test
    void assignRole_alreadyAssigned_throwsRoleAlreadyAssignedException() {
        Role role = roleWithId("ROLE_MEMBER");
        AuthAccount account = accountWithId();
        account.setRoles(new java.util.HashSet<>(Set.of(role)));

        when(authAccountRepository.findById(account.getId())).thenReturn(java.util.Optional.of(account));
        when(roleRepository.findById(role.getId())).thenReturn(java.util.Optional.of(role));

        assertThatThrownBy(() -> authAccountService.assignRole(account.getId(), role.getId()))
                .isInstanceOf(RoleAlreadyAssignedException.class);
    }

    @Test
    void removeRole_withAssignedRole_removesIt() {
        Role role = roleWithId("ROLE_MEMBER");
        AuthAccount account = accountWithId();
        account.setRoles(new java.util.HashSet<>(Set.of(role)));

        when(authAccountRepository.findById(account.getId())).thenReturn(java.util.Optional.of(account));

        authAccountService.removeRole(account.getId(), role.getId());

        assertThat(account.getRoles()).isEmpty();
    }

    @Test
    void removeRole_notAssigned_throwsRoleNotAssignedException() {
        AuthAccount account = accountWithId();
        account.setRoles(new java.util.HashSet<>());

        when(authAccountRepository.findById(account.getId())).thenReturn(java.util.Optional.of(account));

        assertThatThrownBy(() -> authAccountService.removeRole(account.getId(), UUID.randomUUID()))
                .isInstanceOf(RoleNotAssignedException.class);
    }

    @Test
    void removeRole_withUnknownAccount_throwsAccountNotFoundInAdminException() {
        UUID accountId = UUID.randomUUID();
        when(authAccountRepository.findById(accountId)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> authAccountService.removeRole(accountId, UUID.randomUUID()))
                .isInstanceOf(AccountNotFoundInAdminException.class);
    }

    // ------------------------------------------------------------------
    // Décision R21-RD : changement administratif de statut de compte.
    // AuthAccountRepository/RefreshTokenService/AccountStatusChangeRepository
    // sont simulés ici (Mockito) - les preuves SQL réelles de la transition
    // atomique (AuthAccountRepositoryTest) et de la révocation multi-familles
    // (RefreshTokenRepositoryTest) sont apportées séparément ; la preuve sous
    // accès CONCURRENT réel par AccountStatusConcurrencyIntegrationTest.
    // ------------------------------------------------------------------

    @Test
    void changeAccountStatus_activeToLocked_delegatesTransitionRevocationAndAudit() {
        AuthAccount account = accountWithId();
        UUID actorId = UUID.randomUUID();
        when(authAccountRepository.findById(account.getId())).thenReturn(java.util.Optional.of(account));
        when(authAccountRepository.transitionStatusIfAllowed(eq(account.getId()), eq("LOCKED"), any())).thenReturn(1);

        AuthAccount result = authAccountService.changeAccountStatus(
                account.getId(), AccountStatus.LOCKED, "Fraude signalee", actorId);

        assertThat(result).isSameAs(account);
        verify(authAccountRepository).transitionStatusIfAllowed(account.getId(), "LOCKED", List.of("ACTIVE"));
        verify(refreshTokenService).revokeAllForAccount(account.getId());

        ArgumentCaptor<AccountStatusChange> eventCaptor = ArgumentCaptor.forClass(AccountStatusChange.class);
        verify(accountStatusChangeRepository).save(eventCaptor.capture());
        AccountStatusChange event = eventCaptor.getValue();
        assertThat(event.getAccountId()).isEqualTo(account.getId());
        assertThat(event.getActorAccountId()).isEqualTo(actorId);
        assertThat(event.getOldStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(event.getNewStatus()).isEqualTo(AccountStatus.LOCKED);
        assertThat(event.getReason()).isEqualTo("Fraude signalee");
    }

    @Test
    void changeAccountStatus_disabledToLocked_throwsInvalidAccountStatusTransitionException_withoutAnyWrite() {
        // Decision R21-RD D2 : transition explicitement interdite.
        AuthAccount account = accountWithId();
        account.setStatus(AccountStatus.DISABLED);
        when(authAccountRepository.findById(account.getId())).thenReturn(java.util.Optional.of(account));

        assertThatThrownBy(() -> authAccountService.changeAccountStatus(
                account.getId(), AccountStatus.LOCKED, "Motif quelconque", UUID.randomUUID()))
                .isInstanceOf(InvalidAccountStatusTransitionException.class);

        verify(authAccountRepository, never()).transitionStatusIfAllowed(any(), any(), any());
        verify(refreshTokenService, never()).revokeAllForAccount(any());
        verify(accountStatusChangeRepository, never()).save(any());
    }

    @Test
    void changeAccountStatus_targetAlreadyApplied_isIdempotent_withoutAnyWrite() {
        // Decision R21-RD D3 : idempotence, aucune ecriture, aucun audit, aucune revocation.
        AuthAccount account = accountWithId(); // ACTIVE
        when(authAccountRepository.findById(account.getId())).thenReturn(java.util.Optional.of(account));

        AuthAccount result = authAccountService.changeAccountStatus(
                account.getId(), AccountStatus.ACTIVE, "Motif quelconque", UUID.randomUUID());

        assertThat(result).isSameAs(account);
        verify(authAccountRepository, never()).transitionStatusIfAllowed(any(), any(), any());
        verify(refreshTokenService, never()).revokeAllForAccount(any());
        verify(accountStatusChangeRepository, never()).save(any());
    }

    @Test
    void changeAccountStatus_withUnknownAccount_throwsAccountNotFoundInAdminException() {
        UUID accountId = UUID.randomUUID();
        when(authAccountRepository.findById(accountId)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> authAccountService.changeAccountStatus(
                accountId, AccountStatus.LOCKED, "Motif quelconque", UUID.randomUUID()))
                .isInstanceOf(AccountNotFoundInAdminException.class);
    }

    @Test
    void changeAccountStatus_lostRaceButTargetReachedByAnotherThread_isTreatedAsIdempotentSuccess() {
        // Course perdue (transitionStatusIfAllowed renvoie 0) mais la relecture montre
        // qu'un autre thread a deja applique EXACTEMENT la transition demandee. La relecture
        // passe par entityManager.refresh(account) (et non un second findById - voir javadoc
        // de changeAccountStatus) : on simule ici l'effet reel de refresh() en mutant l'entite
        // DEJA geree, exactement comme le ferait Hibernate en reponse a une vraie requete SQL.
        AuthAccount account = accountWithId(); // lu ACTIVE au depart
        when(authAccountRepository.findById(account.getId())).thenReturn(java.util.Optional.of(account));
        when(authAccountRepository.transitionStatusIfAllowed(eq(account.getId()), eq("LOCKED"), any())).thenReturn(0);
        org.mockito.Mockito.doAnswer(invocation -> {
            account.setStatus(AccountStatus.LOCKED); // deja applique par un autre thread
            return null;
        }).when(entityManager).refresh(account);

        AuthAccount result = authAccountService.changeAccountStatus(
                account.getId(), AccountStatus.LOCKED, "Motif quelconque", UUID.randomUUID());

        assertThat(result).isSameAs(account);
        assertThat(result.getStatus()).isEqualTo(AccountStatus.LOCKED);
        verify(refreshTokenService, never()).revokeAllForAccount(any());
        verify(accountStatusChangeRepository, never()).save(any());
    }

    @Test
    void changeAccountStatus_lostRaceAndStillInvalidFromRealState_throwsInvalidAccountStatusTransitionException() {
        // Course perdue, et l'etat reel relu (via entityManager.refresh) ne correspond ni a la
        // cible ni a une source valide pour celle-ci (ex. un autre thread a desactive le compte
        // entre-temps).
        AuthAccount account = accountWithId(); // lu ACTIVE au depart
        when(authAccountRepository.findById(account.getId())).thenReturn(java.util.Optional.of(account));
        when(authAccountRepository.transitionStatusIfAllowed(eq(account.getId()), eq("LOCKED"), any())).thenReturn(0);
        org.mockito.Mockito.doAnswer(invocation -> {
            account.setStatus(AccountStatus.DISABLED);
            return null;
        }).when(entityManager).refresh(account);

        assertThatThrownBy(() -> authAccountService.changeAccountStatus(
                account.getId(), AccountStatus.LOCKED, "Motif quelconque", UUID.randomUUID()))
                .isInstanceOf(InvalidAccountStatusTransitionException.class);

        verify(refreshTokenService, never()).revokeAllForAccount(any());
        verify(accountStatusChangeRepository, never()).save(any());
    }

    private static AuthAccount accountWithId() {
        AuthAccount account = new AuthAccount();
        account.setId(UUID.randomUUID());
        account.setEmail("rbac-target@tontiflow.test");
        account.setPasswordHash("test-only-not-a-real-hash");
        account.setStatus(AccountStatus.ACTIVE);
        return account;
    }

    private static Role roleWithId(String name) {
        Role role = new Role();
        role.setId(UUID.randomUUID());
        role.setName(name);
        return role;
    }

    private AuthAccount activeAccount(String email, String rawPassword) {
        return accountWithStatus(email, rawPassword, AccountStatus.ACTIVE);
    }

    private AuthAccount accountWithStatus(String email, String rawPassword, AccountStatus status) {
        AuthAccount account = new AuthAccount();
        account.setId(UUID.randomUUID());
        account.setEmail(email);
        account.setPasswordHash(passwordEncoder.encode(rawPassword));
        account.setStatus(status);
        return account;
    }

    private static Role role(String name, Set<Permission> permissions) {
        Role role = new Role();
        role.setId(UUID.randomUUID());
        role.setName(name);
        role.setPermissions(permissions);
        return role;
    }

    private static Permission permission(String name) {
        Permission permission = new Permission();
        permission.setId(UUID.randomUUID());
        permission.setName(name);
        return permission;
    }
}
