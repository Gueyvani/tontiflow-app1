package com.tontiflow.interfaces.rest;

import com.tontiflow.UserContext;
import com.tontiflow.domain.enums.AccountStatus;
import com.tontiflow.domain.model.AuthAccount;
import com.tontiflow.domain.model.Permission;
import com.tontiflow.domain.model.Role;
import com.tontiflow.infrastructure.repository.AuthAccountRepository;
import com.tontiflow.infrastructure.repository.PermissionRepository;
import com.tontiflow.infrastructure.repository.RoleRepository;
import com.tontiflow.infrastructure.security.jwt.AccessTokenService;
import com.tontiflow.infrastructure.security.jwt.JwtTestSecurityConfiguration;
import com.tontiflow.interfaces.rest.dto.UpdateAccountStatusRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Décision F-4 (vrai HTTP, vraie base) : garde-fous d'intégrité des administrateurs.
 * <ul>
 *   <li>A/B : un administrateur ne se désactive ni ne se verrouille lui-même ;</li>
 *   <li>C : il ne se retire pas {@code ROLE_ADMIN} ;</li>
 *   <li>D/E/F : le dernier administrateur <b>actif</b> ne peut ni perdre le rôle, ni être désactivé ni
 *       verrouillé ;</li>
 *   <li>G/H : avec un autre administrateur actif, ces opérations sont autorisées ;</li>
 *   <li>I : un administrateur {@code DISABLED} ou une permission nommée {@code ROLE_ADMIN} n'est jamais
 *       compté ;</li>
 *   <li>J : deux opérations croisées simultanées ne laissent jamais zéro administrateur actif ; le
 *       verrou {@code ROLE_ADMIN} est réellement pris.</li>
 * </ul>
 *
 * <p>Base H2 <b>dédiée</b> (URL propre, donc contexte Spring séparé) : la base partagée des autres tests
 * contient des administrateurs actifs laissés par ces tests, ce qui rendrait « le dernier administrateur »
 * invisible. Chaque test repart d'une base sans compte. Les acteurs portent {@code ROLE_ADMIN} dans leur
 * JWT (valable 15 min, décision D6) : un acteur peut donc être un administrateur déjà désactivé en base
 * (fenêtre documentée, hors périmètre F-4), ce qui permet de tester la règle du dernier administrateur
 * indépendamment de la règle d'auto-modification.</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:f4guard;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE")
@ActiveProfiles("test")
@Import(JwtTestSecurityConfiguration.class)
class AdminGuardrailIntegrationTest {

    private static final String ADMIN = "ROLE_ADMIN";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AuthAccountRepository authAccountRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private AccessTokenService accessTokenService;

    @Autowired
    private DataSource dataSource;

    private Role adminRole;

    @BeforeEach
    void freshDatabaseState() {
        authAccountRepository.deleteAll();
        adminRole = roleRepository.findByName(ADMIN).orElseGet(() -> {
            Role role = new Role();
            role.setName(ADMIN);
            return roleRepository.save(role);
        });
    }

    // ------------------------------------------------------------------
    // A / B : auto-desactivation et auto-verrouillage
    // ------------------------------------------------------------------

    @Test
    void adminCannotDisableOrLockHisOwnAccount_evenWithAnotherActiveAdmin() {
        AuthAccount self = account("self-status", AccountStatus.ACTIVE, Set.of(adminRole));
        account("other-admin", AccountStatus.ACTIVE, Set.of(adminRole));

        for (AccountStatus target : List.of(AccountStatus.DISABLED, AccountStatus.LOCKED)) {
            ResponseEntity<String> response = putStatus(self, self, target);

            assertConflictWithGenericMessage(response);
        }
        assertThat(statusOf(self)).isEqualTo(AccountStatus.ACTIVE);
    }

    // ------------------------------------------------------------------
    // C : auto-retrait de ROLE_ADMIN
    // ------------------------------------------------------------------

    @Test
    void adminCannotRemoveRoleAdminFromHimself_evenWithAnotherActiveAdmin() {
        AuthAccount self = account("self-role", AccountStatus.ACTIVE, Set.of(adminRole));
        account("other-admin", AccountStatus.ACTIVE, Set.of(adminRole));

        ResponseEntity<String> response = deleteRole(self, self, adminRole);

        assertConflictWithGenericMessage(response);
        assertThat(rolesOf(self)).contains(ADMIN);
    }

    @Test
    void removingANonAdminRoleFromYourself_isNeverGuarded() {
        Role member = role("ROLE_F4_MEMBER");
        AuthAccount self = account("self-member", AccountStatus.ACTIVE, Set.of(adminRole, member));

        ResponseEntity<String> response = deleteRole(self, self, member);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(rolesOf(self)).containsExactly(ADMIN);
    }

    // ------------------------------------------------------------------
    // D / E / F : dernier administrateur ACTIF (regle C, independante de la regle d'auto-modification :
    // l'acteur est un autre compte, ici un administrateur deja DISABLED dont le JWT est encore valide)
    // ------------------------------------------------------------------

    @Test
    void lastActiveAdminCannotLoseRoleAdmin() {
        AuthAccount lastAdmin = account("last-role", AccountStatus.ACTIVE, Set.of(adminRole));
        AuthAccount staleActor = account("stale-actor-1", AccountStatus.DISABLED, Set.of(adminRole));

        ResponseEntity<String> response = deleteRole(staleActor, lastAdmin, adminRole);

        assertConflictWithGenericMessage(response);
        assertThat(rolesOf(lastAdmin)).contains(ADMIN);
    }

    @Test
    void lastActiveAdminCannotBeDisabled() {
        AuthAccount lastAdmin = account("last-disable", AccountStatus.ACTIVE, Set.of(adminRole));
        AuthAccount staleActor = account("stale-actor-2", AccountStatus.DISABLED, Set.of(adminRole));

        ResponseEntity<String> response = putStatus(staleActor, lastAdmin, AccountStatus.DISABLED);

        assertConflictWithGenericMessage(response);
        assertThat(statusOf(lastAdmin)).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void lastActiveAdminCannotBeLocked() {
        AuthAccount lastAdmin = account("last-lock", AccountStatus.ACTIVE, Set.of(adminRole));
        AuthAccount staleActor = account("stale-actor-3", AccountStatus.DISABLED, Set.of(adminRole));

        ResponseEntity<String> response = putStatus(staleActor, lastAdmin, AccountStatus.LOCKED);

        assertConflictWithGenericMessage(response);
        assertThat(statusOf(lastAdmin)).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void aPermissionNamedRoleAdmin_isNeverCountedAsAnAdmin() {
        AuthAccount lastAdmin = account("last-perm", AccountStatus.ACTIVE, Set.of(adminRole));
        AuthAccount staleActor = account("stale-actor-4", AccountStatus.DISABLED, Set.of(adminRole));
        // Un compte ACTIF qui n'a PAS le role, seulement une permission portant le nom "ROLE_ADMIN".
        Permission fake = new Permission();
        fake.setName(ADMIN);
        fake = permissionRepository.save(fake);
        Role carrier = new Role();
        carrier.setName("ROLE_F4_PERMISSION_CARRIER");
        carrier.setPermissions(new HashSet<>(Set.of(fake)));
        carrier = roleRepository.save(carrier);
        account("permission-holder", AccountStatus.ACTIVE, Set.of(carrier));

        ResponseEntity<String> response = putStatus(staleActor, lastAdmin, AccountStatus.DISABLED);

        assertConflictWithGenericMessage(response);
        assertThat(statusOf(lastAdmin)).isEqualTo(AccountStatus.ACTIVE);
    }

    // ------------------------------------------------------------------
    // G / H / I : avec un autre administrateur actif, ou une cible non active, tout reste autorise
    // ------------------------------------------------------------------

    @Test
    void withTwoActiveAdmins_removingTheOthersRoleIsAllowed() {
        AuthAccount actor = account("g-actor", AccountStatus.ACTIVE, Set.of(adminRole));
        AuthAccount other = account("g-other", AccountStatus.ACTIVE, Set.of(adminRole));

        ResponseEntity<String> response = deleteRole(actor, other, adminRole);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(rolesOf(other)).isEmpty();
        assertThat(rolesOf(actor)).contains(ADMIN);
    }

    @Test
    void withTwoActiveAdmins_disablingOrLockingTheOtherIsAllowed() {
        AuthAccount actor = account("h-actor", AccountStatus.ACTIVE, Set.of(adminRole));
        AuthAccount toDisable = account("h-disable", AccountStatus.ACTIVE, Set.of(adminRole));
        AuthAccount toLock = account("h-lock", AccountStatus.ACTIVE, Set.of(adminRole));

        assertThat(putStatus(actor, toDisable, AccountStatus.DISABLED).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(putStatus(actor, toLock, AccountStatus.LOCKED).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(statusOf(toDisable)).isEqualTo(AccountStatus.DISABLED);
        assertThat(statusOf(toLock)).isEqualTo(AccountStatus.LOCKED);
        assertThat(statusOf(actor)).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void anAlreadyDisabledAdmin_isNotCounted_andNeverBlocksTheActiveOne() {
        AuthAccount actor = account("i-actor", AccountStatus.ACTIVE, Set.of(adminRole));
        AuthAccount disabledAdmin = account("i-disabled", AccountStatus.DISABLED, Set.of(adminRole));

        // Retirer le role a un administrateur deja DISABLED ne reduit pas les administrateurs ACTIFS.
        ResponseEntity<String> response = deleteRole(actor, disabledAdmin, adminRole);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(rolesOf(disabledAdmin)).isEmpty();
    }

    @Test
    void reactivatingAnAdmin_isNeverGuarded() {
        AuthAccount actor = account("react-actor", AccountStatus.ACTIVE, Set.of(adminRole));
        AuthAccount disabledAdmin = account("react-target", AccountStatus.DISABLED, Set.of(adminRole));

        assertThat(putStatus(actor, disabledAdmin, AccountStatus.ACTIVE).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statusOf(disabledAdmin)).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void removingAnAdminRoleThatIsNoLongerAssigned_keepsTheExistingNotFoundBehavior() {
        AuthAccount actor = account("nf-actor", AccountStatus.ACTIVE, Set.of(adminRole));
        AuthAccount plain = account("nf-plain", AccountStatus.ACTIVE, Set.of());

        assertThat(deleteRole(actor, plain, adminRole).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // Meme pour soi-meme : le role n'est pas attribue -> 404 existant, pas de garde-fou.
        assertThat(deleteRole(plain, plain, adminRole).getStatusCode()).isNotEqualTo(HttpStatus.CONFLICT);
    }

    // ------------------------------------------------------------------
    // J : concurrence - jamais zero administrateur actif
    // ------------------------------------------------------------------

    @Test
    void twoCrossedRoleRemovals_neverLeaveZeroActiveAdmin_exactlyOneSucceeds_repeated() throws Exception {
        for (int round = 0; round < 12; round++) {
            authAccountRepository.deleteAll();
            AuthAccount a = account("cross-a-" + round, AccountStatus.ACTIVE, Set.of(adminRole));
            AuthAccount b = account("cross-b-" + round, AccountStatus.ACTIVE, Set.of(adminRole));

            List<HttpStatus> results = runConcurrently(
                    () -> deleteRole(a, b, adminRole).getStatusCode().is2xxSuccessful() ? HttpStatus.NO_CONTENT : HttpStatus.CONFLICT,
                    () -> deleteRole(b, a, adminRole).getStatusCode().is2xxSuccessful() ? HttpStatus.NO_CONTENT : HttpStatus.CONFLICT);

            assertThat(results).as("round %d", round).containsExactlyInAnyOrder(HttpStatus.NO_CONTENT, HttpStatus.CONFLICT);
            assertThat(activeAdminCount()).as("round %d", round).isEqualTo(1);
        }
    }

    @Test
    void twoCrossedDisables_neverLeaveZeroActiveAdmin_exactlyOneSucceeds_repeated() throws Exception {
        for (int round = 0; round < 12; round++) {
            authAccountRepository.deleteAll();
            AuthAccount a = account("dis-a-" + round, AccountStatus.ACTIVE, Set.of(adminRole));
            AuthAccount b = account("dis-b-" + round, AccountStatus.ACTIVE, Set.of(adminRole));

            List<HttpStatus> results = runConcurrently(
                    () -> putStatus(a, b, AccountStatus.DISABLED).getStatusCode().is2xxSuccessful() ? HttpStatus.OK : HttpStatus.CONFLICT,
                    () -> putStatus(b, a, AccountStatus.DISABLED).getStatusCode().is2xxSuccessful() ? HttpStatus.OK : HttpStatus.CONFLICT);

            assertThat(results).as("round %d", round).containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
            assertThat(activeAdminCount()).as("round %d", round).isEqualTo(1);
        }
    }

    @Test
    void mixedCrossedOperations_neverLeaveZeroActiveAdmin_repeated() throws Exception {
        for (int round = 0; round < 12; round++) {
            authAccountRepository.deleteAll();
            AuthAccount a = account("mix-a-" + round, AccountStatus.ACTIVE, Set.of(adminRole));
            AuthAccount b = account("mix-b-" + round, AccountStatus.ACTIVE, Set.of(adminRole));

            List<HttpStatus> results = runConcurrently(
                    () -> deleteRole(a, b, adminRole).getStatusCode().is2xxSuccessful() ? HttpStatus.OK : HttpStatus.CONFLICT,
                    () -> putStatus(b, a, AccountStatus.LOCKED).getStatusCode().is2xxSuccessful() ? HttpStatus.OK : HttpStatus.CONFLICT);

            assertThat(results).as("round %d", round).containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
            assertThat(activeAdminCount()).as("round %d", round).isEqualTo(1);
        }
    }

    /**
     * Preuve que le verrou ROLE_ADMIN est reellement pris : une transaction externe le detient, l'operation
     * qui peut reduire les administrateurs doit ATTENDRE (jamais terminer), puis aboutir apres liberation.
     * Un retrait d'un role NON administrateur ne doit jamais attendre ce verrou.
     */
    @Test
    void adminReducingOperation_waitsForTheAdminRoleLock_butOtherRoleRemovalDoesNot() throws Exception {
        AuthAccount actor = account("lock-actor", AccountStatus.ACTIVE, Set.of(adminRole));
        AuthAccount target = account("lock-target", AccountStatus.ACTIVE, Set.of(adminRole));
        Role member = role("ROLE_F4_LOCK_MEMBER");
        AuthAccount memberHolder = account("lock-member", AccountStatus.ACTIVE, Set.of(member));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection lockHolder = dataSource.getConnection()) {
            lockHolder.setAutoCommit(false);
            try (var statement = lockHolder.createStatement()) {
                statement.execute("SELECT id FROM role WHERE name = 'ROLE_ADMIN' FOR UPDATE");
            }

            // Ne touche pas au verrou ROLE_ADMIN : doit terminer pendant que le verrou est tenu.
            Future<HttpStatus> nonAdmin = pool.submit(() ->
                    HttpStatus.valueOf(deleteRole(actor, memberHolder, member).getStatusCode().value()));
            assertThat(nonAdmin.get(20, TimeUnit.SECONDS)).isEqualTo(HttpStatus.NO_CONTENT);

            Future<HttpStatus> guarded = pool.submit(() ->
                    HttpStatus.valueOf(deleteRole(actor, target, adminRole).getStatusCode().value()));
            Thread.sleep(700); // < timeout de verrou H2 (2 s) : l'operation garde doit rester bloquee
            assertThat(guarded.isDone()).as("l'operation doit attendre le verrou ROLE_ADMIN").isFalse();

            lockHolder.rollback(); // libere le verrou
            assertThat(guarded.get(20, TimeUnit.SECONDS)).isEqualTo(HttpStatus.NO_CONTENT);
        } finally {
            pool.shutdownNow();
        }
        assertThat(rolesOf(target)).isEmpty();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private List<HttpStatus> runConcurrently(java.util.concurrent.Callable<HttpStatus> first,
                                             java.util.concurrent.Callable<HttpStatus> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<HttpStatus> f1 = pool.submit(() -> {
                start.await();
                return first.call();
            });
            Future<HttpStatus> f2 = pool.submit(() -> {
                start.await();
                return second.call();
            });
            start.countDown();
            return List.of(f1.get(60, TimeUnit.SECONDS), f2.get(60, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    private AuthAccount account(String label, AccountStatus status, Set<Role> roles) {
        AuthAccount account = new AuthAccount();
        account.setEmail(label + "-" + UUID.randomUUID() + "@tontiflow.test");
        account.setPasswordHash("test-only-not-a-real-hash");
        account.setStatus(status);
        account.setRoles(new HashSet<>(roles));
        return authAccountRepository.save(account);
    }

    private Role role(String name) {
        return roleRepository.findByName(name).orElseGet(() -> {
            Role role = new Role();
            role.setName(name);
            return roleRepository.save(role);
        });
    }

    private String tokenOf(AuthAccount actor) {
        // JWT porte ROLE_ADMIN comme un vrai jeton emis avant un changement de statut (decision D6).
        return accessTokenService.generate(
                new UserContext(actor.getId(), actor.getEmail(), actor.getEmail(), Set.of(ADMIN), Set.of()));
    }

    private ResponseEntity<String> putStatus(AuthAccount actor, AuthAccount target, AccountStatus status) {
        return restTemplate.exchange(
                "/api/v1/admin/accounts/" + target.getId() + "/status", HttpMethod.PUT,
                new HttpEntity<>(new UpdateAccountStatusRequest(status, "Motif F-4"), bearer(actor)),
                String.class);
    }

    private ResponseEntity<String> deleteRole(AuthAccount actor, AuthAccount target, Role role) {
        return restTemplate.exchange(
                "/api/v1/admin/accounts/" + target.getId() + "/roles/" + role.getId(), HttpMethod.DELETE,
                new HttpEntity<>(null, bearer(actor)), String.class);
    }

    private HttpHeaders bearer(AuthAccount actor) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tokenOf(actor));
        return headers;
    }

    private void assertConflictWithGenericMessage(ResponseEntity<String> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        // Message generique fixe : ne revele jamais quelle regle a joue.
        assertThat(response.getBody()).contains("\"detail\":\"Operation not allowed\"");
    }

    private AccountStatus statusOf(AuthAccount account) {
        return authAccountRepository.findById(account.getId()).orElseThrow().getStatus();
    }

    private Set<String> rolesOf(AuthAccount account) {
        return roleNamesOf(account.getId());
    }

    private Set<String> roleNamesOf(UUID accountId) {
        // Lecture SQL directe : les roles sont LAZY et il n'y a pas de session ouverte dans ce test.
        return new HashSet<>(new org.springframework.jdbc.core.JdbcTemplate(dataSource).queryForList(
                "SELECT r.name FROM account_role ar JOIN role r ON r.id = ar.role_id WHERE ar.account_id = ?",
                String.class, accountId));
    }

    private long activeAdminCount() {
        return authAccountRepository.countByRoleNameAndStatusExcludingAccount(
                ADMIN, AccountStatus.ACTIVE, UUID.randomUUID());
    }
}
