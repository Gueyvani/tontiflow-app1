package com.tontiflow.application.service;

import com.tontiflow.application.exception.AdminGuardrailViolationException;
import com.tontiflow.domain.enums.AccountStatus;
import com.tontiflow.domain.model.AuthAccount;
import com.tontiflow.domain.model.Role;
import com.tontiflow.infrastructure.repository.AuthAccountRepository;
import com.tontiflow.infrastructure.repository.RoleRepository;
import com.tontiflow.infrastructure.security.jwt.JwtTestSecurityConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Décision F-4 sur un vrai PostgreSQL (Testcontainers, postgres:16-alpine, Flyway V1..V6 + Hibernate
 * {@code validate}) : ce que H2 ne peut pas prouver - sémantique réelle du verrou de ligne
 * {@code SELECT ... FOR UPDATE} sur {@code ROLE_ADMIN} (seedé par V2), isolation {@code READ COMMITTED},
 * et absence d'interblocage avec les verrous de clé partagée que PostgreSQL prend sur la ligne du rôle lors
 * d'un {@code INSERT} dans {@code account_role}. Exécuté uniquement via le profil Maven
 * {@code postgres-integration} (Docker requis).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(JwtTestSecurityConfiguration.class)
@Testcontainers
class AdminGuardrailPostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("auth_f4_test")
            .withUsername("tc_user")
            .withPassword("tc_password");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    private static final String ADMIN = "ROLE_ADMIN";
    private static final int ROUNDS = 25;

    @Autowired
    private AuthAccountService authAccountService;

    @Autowired
    private AuthAccountRepository authAccountRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;
    private Role adminRole;

    @BeforeEach
    void resetData() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM account_status_change");
        jdbc.update("DELETE FROM refresh_token");
        jdbc.update("DELETE FROM account_role");
        jdbc.update("DELETE FROM auth_account");
        // Seed V2 : la ligne ROLE_ADMIN existe deja (identifiant fixe).
        adminRole = roleRepository.findByName(ADMIN).orElseThrow();
    }

    private AuthAccount account(AccountStatus status, boolean admin) {
        AuthAccount account = new AuthAccount();
        account.setEmail("f4-pg-" + UUID.randomUUID() + "@tontiflow.test");
        account.setPasswordHash("test-only-not-a-real-hash");
        account.setStatus(status);
        account.setRoles(admin ? new HashSet<>(Set.of(adminRole)) : new HashSet<>());
        return authAccountRepository.save(account);
    }

    private long activeAdmins() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM auth_account a
                JOIN account_role ar ON ar.account_id = a.id
                JOIN role r ON r.id = ar.role_id
                WHERE r.name = 'ROLE_ADMIN' AND a.status = 'ACTIVE'
                """, Long.class);
    }

    /** true = l'operation a abouti ; false = refusee par un garde-fou ; toute autre exception est propagee. */
    private Callable<Boolean> attempt(Runnable operation) {
        return () -> {
            try {
                operation.run();
                return true;
            } catch (AdminGuardrailViolationException refused) {
                return false;
            }
        };
    }

    private List<Boolean> runConcurrently(Callable<Boolean> first, Callable<Boolean> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> f1 = pool.submit(() -> {
                start.await();
                return first.call();
            });
            Future<Boolean> f2 = pool.submit(() -> {
                start.await();
                return second.call();
            });
            start.countDown();
            return List.of(f1.get(60, TimeUnit.SECONDS), f2.get(60, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void migrationV2_seedsTheAdminRoleWhoseRowIsTheMutex() {
        assertThat(adminRole.getId()).isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    }

    @Test
    void crossedRoleRemovals_neverLeaveZeroActiveAdmin_exactlyOneSucceeds() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            jdbc.update("DELETE FROM account_role");
            jdbc.update("DELETE FROM auth_account");
            AuthAccount a = account(AccountStatus.ACTIVE, true);
            AuthAccount b = account(AccountStatus.ACTIVE, true);

            List<Boolean> results = runConcurrently(
                    attempt(() -> authAccountService.removeRole(b.getId(), adminRole.getId(), a.getId())),
                    attempt(() -> authAccountService.removeRole(a.getId(), adminRole.getId(), b.getId())));

            assertThat(results).as("round %d", round).containsExactlyInAnyOrder(true, false);
            assertThat(activeAdmins()).as("round %d", round).isEqualTo(1);
        }
    }

    @Test
    void crossedDisables_neverLeaveZeroActiveAdmin_exactlyOneSucceeds() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            jdbc.update("DELETE FROM account_status_change");
            jdbc.update("DELETE FROM refresh_token");
            jdbc.update("DELETE FROM account_role");
            jdbc.update("DELETE FROM auth_account");
            AuthAccount a = account(AccountStatus.ACTIVE, true);
            AuthAccount b = account(AccountStatus.ACTIVE, true);

            List<Boolean> results = runConcurrently(
                    attempt(() -> authAccountService.changeAccountStatus(b.getId(), AccountStatus.DISABLED, "F-4", a.getId())),
                    attempt(() -> authAccountService.changeAccountStatus(a.getId(), AccountStatus.DISABLED, "F-4", b.getId())));

            assertThat(results).as("round %d", round).containsExactlyInAnyOrder(true, false);
            assertThat(activeAdmins()).as("round %d", round).isEqualTo(1);
        }
    }

    @Test
    void mixedCrossedOperations_neverLeaveZeroActiveAdmin() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            jdbc.update("DELETE FROM account_status_change");
            jdbc.update("DELETE FROM refresh_token");
            jdbc.update("DELETE FROM account_role");
            jdbc.update("DELETE FROM auth_account");
            AuthAccount a = account(AccountStatus.ACTIVE, true);
            AuthAccount b = account(AccountStatus.ACTIVE, true);

            List<Boolean> results = runConcurrently(
                    attempt(() -> authAccountService.removeRole(b.getId(), adminRole.getId(), a.getId())),
                    attempt(() -> authAccountService.changeAccountStatus(a.getId(), AccountStatus.LOCKED, "F-4", b.getId())));

            assertThat(results).as("round %d", round).containsExactlyInAnyOrder(true, false);
            assertThat(activeAdmins()).as("round %d", round).isEqualTo(1);
        }
    }

    /**
     * Le verrou est reellement pris sur PostgreSQL : une transaction externe detient la ligne ROLE_ADMIN,
     * l'operation qui peut reduire les administrateurs attend (observe dans pg_stat_activity : backend en
     * attente d'un verrou), puis aboutit apres liberation. Retirer un role non administrateur n'attend pas.
     */
    @Test
    void adminReducingOperation_waitsOnTheRowLock_observedInPgStatActivity_thenCompletes() throws Exception {
        AuthAccount actor = account(AccountStatus.ACTIVE, true);
        AuthAccount target = account(AccountStatus.ACTIVE, true);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection lockHolder = dataSource.getConnection()) {
            lockHolder.setAutoCommit(false);
            try (var statement = lockHolder.createStatement()) {
                statement.execute("SELECT id FROM role WHERE name = 'ROLE_ADMIN' FOR UPDATE");
            }

            Future<Boolean> guarded = pool.submit(attempt(
                    () -> authAccountService.removeRole(target.getId(), adminRole.getId(), actor.getId())));

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            int waiting = 0;
            while (System.nanoTime() < deadline && waiting == 0) {
                waiting = jdbc.queryForObject("""
                        SELECT count(*) FROM pg_stat_activity
                        WHERE wait_event_type = 'Lock' AND state = 'active'
                          AND query ILIKE '%from role%' AND query ILIKE '%for update%'
                        """, Integer.class);
                Thread.sleep(10);
            }
            assertThat(waiting).as("une operation doit etre observee en attente du verrou ROLE_ADMIN").isGreaterThan(0);
            assertThat(guarded.isDone()).isFalse();

            lockHolder.rollback(); // libere le verrou
            assertThat(guarded.get(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(activeAdmins()).isEqualTo(1);
    }

    /**
     * PostgreSQL prend un verrou de cle partagee sur la ligne du role a chaque INSERT dans account_role
     * (cle etrangere) : il attend brievement le FOR UPDATE de la garde. Aucun interblocage ni exception.
     */
    @Test
    void guardedOperationsAndConcurrentRoleAssignments_neverDeadlock() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            jdbc.update("DELETE FROM account_status_change");
            jdbc.update("DELETE FROM refresh_token");
            jdbc.update("DELETE FROM account_role");
            jdbc.update("DELETE FROM auth_account");
            AuthAccount a = account(AccountStatus.ACTIVE, true);
            AuthAccount b = account(AccountStatus.ACTIVE, true);
            AuthAccount newcomer = account(AccountStatus.ACTIVE, false);

            List<Boolean> results = runConcurrently(
                    attempt(() -> authAccountService.removeRole(b.getId(), adminRole.getId(), a.getId())),
                    attempt(() -> authAccountService.assignRole(newcomer.getId(), adminRole.getId())));

            // Aucune exception autre qu'un refus de garde-fou : aucun DeadlockLoser, aucun timeout.
            assertThat(results).as("round %d", round).hasSize(2);
            assertThat(activeAdmins()).as("round %d", round).isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    void aDisabledAdmin_isNotCounted_onPostgres() {
        AuthAccount active = account(AccountStatus.ACTIVE, true);
        AuthAccount staleActor = account(AccountStatus.DISABLED, true);

        boolean removedFromLastActive = attemptResult(() ->
                authAccountService.removeRole(active.getId(), adminRole.getId(), staleActor.getId()));

        assertThat(removedFromLastActive).isFalse();
        assertThat(activeAdmins()).isEqualTo(1);
    }

    private boolean attemptResult(Runnable operation) {
        try {
            return attempt(operation).call();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
