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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private AuthAccountService authAccountService;

    @BeforeEach
    void setUp() {
        authAccountService = newService(Clock.fixed(FIXED_NOW, ZoneOffset.UTC), "15m");
    }

    private AuthAccountService newService(Clock clock, String failureWindow) {
        return new AuthAccountService(authAccountRepository, roleRepository, passwordEncoder, clock, failureWindow);
    }

    @Test
    void createAccount_withAvailableEmail_createsActiveAccountWithHashedPassword() {
        when(authAccountRepository.existsByEmail("new@tontiflow.test")).thenReturn(false);
        when(authAccountRepository.save(any(AuthAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AuthAccount created = authAccountService.createAccount("new@tontiflow.test", TEST_PASSWORD);

        assertThat(created.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(created.getPasswordHash()).isNotNull().isNotEqualTo(TEST_PASSWORD);
        assertThat(passwordEncoder.matches(TEST_PASSWORD, created.getPasswordHash())).isTrue();
    }

    @Test
    void createAccount_withExistingEmail_throwsDuplicateEmailException() {
        when(authAccountRepository.existsByEmail("duplicate@tontiflow.test")).thenReturn(true);

        assertThatThrownBy(() -> authAccountService.createAccount("duplicate@tontiflow.test", TEST_PASSWORD))
                .isInstanceOf(DuplicateEmailException.class);
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
