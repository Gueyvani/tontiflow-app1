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
import static org.mockito.Mockito.lenient;
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
        authAccountService = newService(Clock.fixed(FIXED_NOW, ZoneOffset.UTC), 5, "15m", "15m");
        // Par defaut "non verrouille" (1 ligne affectee) pour les tests qui atteignent le
        // mot de passe correct sans se soucier du mecanisme de verrouillage lui-meme -
        // verifie explicitement, avec des valeurs de retour dediees, dans les tests
        // "Decision R21-D.3" ci-dessous. lenient() : les tests qui n'exercent jamais ce
        // chemin (createAccount, assignRole, ...) ne stubbent pas cet appel.
        lenient().when(authAccountRepository.resetFailedAttemptsIfNotLocked(any(), any())).thenReturn(1);
    }

    private AuthAccountService newService(Clock clock, int maxFailedAttempts, String failureWindow, String lockDuration) {
        return new AuthAccountService(authAccountRepository, roleRepository, passwordEncoder, clock,
                maxFailedAttempts, failureWindow, lockDuration);
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
    // Décision R21-D.3 : verrouillage temporisé de compte, indépendant du
    // verrouillage manuel (AccountStatus.LOCKED) ci-dessus.
    //
    // Correction P1 (audit concurrence, cf. AuthAccountRepository) : le
    // comptage / la fenêtre / le seuil sont désormais calculés par un UNIQUE
    // UPDATE atomique et conditionnel côté base
    // (registerFailedAttempt / resetFailedAttemptsIfNotLocked), donc non
    // reproductibles avec un repository simulé (Mockito ne peut pas exécuter
    // de SQL). Ces tests unitaires vérifient uniquement la DÉLÉGATION
    // correcte (arguments transmis, branchement sur le résultat renvoyé) ;
    // l'arithmétique elle-même (fenêtre, seuil, non-prolongation pendant un
    // verrou actif) est prouvée par de vraies requêtes SQL dans
    // AuthAccountRepositoryTest (H2 réel, séquentiel) et par
    // AccountLockoutConcurrencyIntegrationTest (H2 réel, accès concurrent).
    // ------------------------------------------------------------------

    @Test
    void authenticate_withWrongPassword_delegatesToRegisterFailedAttempt_withComputedWindowAndLockBounds() {
        AuthAccount account = activeAccount("lockout1@tontiflow.test", TEST_PASSWORD);
        when(authAccountRepository.findByEmail("lockout1@tontiflow.test")).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> authAccountService.authenticate("lockout1@tontiflow.test", "mauvais-mot-de-passe"))
                .isInstanceOf(InvalidCredentialsException.class);

        // Verifie que le service calcule et transmet exactement les bornes attendues a
        // l'UPDATE atomique : fenetre glissante de 15 min, seuil 5, echeance de
        // verrouillage a 15 min - sans jamais lire/ecrire ces champs lui-meme en Java.
        verify(authAccountRepository).registerFailedAttempt(
                account.getId(), FIXED_NOW, FIXED_NOW.minus(Duration.ofMinutes(15)), 5, FIXED_NOW.plus(Duration.ofMinutes(15)));
    }

    @Test
    void authenticate_correctPasswordButResetReturnsZero_deniesWithGenericException_notAccountLockedException() {
        // Simule un compte actuellement sous verrouillage temporise : l'UPDATE atomique
        // n'affecte aucune ligne (WHERE non satisfaite cote base). La reponse doit rester
        // IDENTIQUE a un mot de passe incorrect - jamais AccountLockedException/423
        // (anti-enumeration, decision R21-D.3).
        AuthAccount account = activeAccount("lockout2@tontiflow.test", TEST_PASSWORD);
        when(authAccountRepository.findByEmail("lockout2@tontiflow.test")).thenReturn(Optional.of(account));
        when(authAccountRepository.resetFailedAttemptsIfNotLocked(any(), any())).thenReturn(0);

        assertThatThrownBy(() -> authAccountService.authenticate("lockout2@tontiflow.test", TEST_PASSWORD))
                .isInstanceOf(InvalidCredentialsException.class)
                .isNotInstanceOf(AccountLockedException.class);
    }

    @Test
    void authenticate_correctPasswordAndResetSucceeds_delegatesWithCurrentTime_andProceeds() {
        AuthAccount account = activeAccount("lockout3@tontiflow.test", TEST_PASSWORD);
        when(authAccountRepository.findByEmail("lockout3@tontiflow.test")).thenReturn(Optional.of(account));
        when(authAccountRepository.resetFailedAttemptsIfNotLocked(account.getId(), FIXED_NOW)).thenReturn(1);

        AuthAccount result = authAccountService.authenticate("lockout3@tontiflow.test", TEST_PASSWORD);

        assertThat(result).isSameAs(account);
        verify(authAccountRepository).resetFailedAttemptsIfNotLocked(account.getId(), FIXED_NOW);
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
