package com.tontiflow.application.service;

import com.tontiflow.domain.model.AuthAccount;
import com.tontiflow.infrastructure.repository.AuthAccountRepository;
import com.tontiflow.infrastructure.security.jwt.JwtTestSecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Preuve PostgreSQL REEL (Testcontainers, {@code postgres:16-alpine}, identique a
 * la production) de la contention du verrou de compte sur le chemin de login
 * (TICKET-3, complement du test H2 de TICKET-2 : {@link
 * LoginLockContentionObservabilityIntegrationTest}).
 *
 * <p>Base PostgreSQL vierge, propre a ce test (aucun lien avec {@code
 * authentication_db} du docker-compose de developpement). Le schema est cree
 * par <b>Flyway</b> (migrations reelles V1 a V5) et valide par Hibernate
 * ({@code ddl-auto=validate}, comme en production) : aucun schema parallele.</p>
 *
 * <p>Transaction A (JDBC, {@code SELECT ... FOR UPDATE}) tient la ligne ;
 * transaction B execute un vrai {@link AuthAccountService#login} (mot de passe
 * correct). Le blocage est observe dans PostgreSQL via {@code pg_stat_activity}
 * et {@code pg_blocking_pids()} (PID de A dans les bloqueurs de B, {@code
 * wait_event_type = 'Lock'}, requete bloquee = {@code UPDATE auth_account}) de
 * facon continue pendant plus que le seuil applicatif ; A n'est liberee qu'apres.
 * Aucun {@code future.get(timeout)} ni {@code Thread.sleep()} ne sert de preuve.</p>
 *
 * <p><b>Prerequis</b> : Docker. Sans Docker, ce test echoue (il n'est pas ignore
 * silencieusement).</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(JwtTestSecurityConfiguration.class)
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class LoginLockContentionPostgresIntegrationTest {

    private static final String TEST_PASSWORD = "S3cur3-Test-Passw0rd!";
    private static final String EVENT = "login_lock_contention";
    private static final long THRESHOLD_MILLIS = 200;
    private static final long HOLD_WHILE_OBSERVED_BLOCKED_NANOS = TimeUnit.MILLISECONDS.toNanos(300);
    private static final long OBSERVATION_DEADLINE_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final long POLL_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(5);
    private static final Pattern WAIT_MS = Pattern.compile("attente d'acquisition du verrou de compte (\\d+) ms");
    private static final Pattern HIBERNATE_ROW_LOCK_SQL =
            Pattern.compile("^Hibernate: .*auth_account.*\\bfor (no key )?update\\b.*$", Pattern.CASE_INSENSITIVE);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("auth_contention_test")
            .withUsername("tc_user")
            .withPassword("tc_password");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        // Le profil "test" (H2) desactive Flyway et cree le schema via Hibernate : ici, meme
        // configuration qu'en production (Flyway + validate), uniquement pour ce contexte.
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.show-sql", () -> "true");
    }

    @Autowired
    private AuthAccountService authAccountService;

    @Autowired
    private AuthAccountRepository authAccountRepository;

    @Autowired
    private DataSource dataSource;

    @Test
    void flywayMigrationsV1toV5_areAppliedOnRealPostgres() throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM flyway_schema_history WHERE success AND version IS NOT NULL");
             ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).as("migrations V1..V5 executees avec succes").isEqualTo(5);
        }
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM information_schema.tables WHERE table_name = 'account_status_change'");
             ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    void login_blockedOnRowLockAboveThreshold_isObservedByPostgresAndLogsSingleWarning(CapturedOutput output)
            throws Exception {
        String email = "pg-lock-" + UUID.randomUUID() + "@tontiflow.test";
        authAccountService.createAccount(email, TEST_PASSWORD);
        AuthAccount account = authAccountRepository.findByEmail(email).orElseThrow();
        UUID accountId = account.getId();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        long holdMillis;
        try (Connection transactionA = dataSource.getConnection();
             Connection observer = dataSource.getConnection()) {

            // A : detient le verrou de ligne, transaction ouverte.
            transactionA.setAutoCommit(false);
            int pidOfA;
            try (PreparedStatement ps = transactionA.prepareStatement("SELECT pg_backend_pid()");
                 ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                pidOfA = rs.getInt(1);
            }
            try (PreparedStatement ps = transactionA.prepareStatement(
                    "SELECT id FROM auth_account WHERE id = ? FOR UPDATE")) {
                ps.setObject(1, accountId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).as("A detient la ligne (FOR UPDATE)").isTrue();
                }
            }

            // B : vrai login() (mot de passe correct), thread distinct.
            Future<AuthAccountService.LoginResult> loginB =
                    executor.submit(() -> authAccountService.login(email, TEST_PASSWORD));

            // Observation PostgreSQL : B en attente d'un verrou, A dans ses bloqueurs, en continu.
            long firstBlockedNanos = -1;
            int pidOfB = -1;
            String blockedQuery = null;
            long deadline = System.nanoTime() + OBSERVATION_DEADLINE_NANOS;
            long heldNanos = 0;
            while (System.nanoTime() < deadline) {
                Blocked blocked = blockedBy(observer, pidOfA);
                long now = System.nanoTime();
                if (blocked != null) {
                    assertThat(blocked.waitEventType()).as("B attend un verrou (wait_event_type)").isEqualTo("Lock");
                    if (firstBlockedNanos < 0) {
                        firstBlockedNanos = now;
                        pidOfB = blocked.pid();
                        blockedQuery = blocked.query();
                    }
                    assertThat(blocked.pid()).as("meme session bloquee tout du long").isEqualTo(pidOfB);
                    assertThat(blocked.blockers()).as("A reste le bloqueur de B").contains(pidOfA);
                    heldNanos = now - firstBlockedNanos;
                    if (heldNanos >= HOLD_WHILE_OBSERVED_BLOCKED_NANOS) {
                        break;
                    }
                } else if (firstBlockedNanos >= 0) {
                    throw new AssertionError("B a cesse d'etre bloquee avant la liberation de A");
                }
                LockSupport.parkNanos(POLL_INTERVAL_NANOS); // intervalle de sondage PostgreSQL uniquement
            }
            assertThat(blockedQuery).as("PostgreSQL doit designer B comme bloquee par A").isNotNull();
            assertThat(pidOfB).isNotEqualTo(pidOfA);
            assertThat(blockedQuery.toLowerCase())
                    .as("la requete bloquee est le UPDATE de resetFailedAttempts")
                    .contains("update auth_account");
            assertThat(heldNanos).isGreaterThanOrEqualTo(HOLD_WHILE_OBSERVED_BLOCKED_NANOS);
            assertThat(loginB.isDone()).isFalse();
            holdMillis = TimeUnit.NANOSECONDS.toMillis(heldNanos);

            // Liberation de A uniquement apres cette observation.
            transactionA.commit();
            assertThat(loginB.get(30, TimeUnit.SECONDS).refreshToken()).isNotNull();
        } finally {
            executor.shutdownNow();
        }

        // Etat final du compte.
        AuthAccount reloaded = authAccountRepository.findById(accountId).orElseThrow();
        assertThat(reloaded.getFailedAttempts()).isZero();

        // WARN applicatif : un seul, duree >= seuil et >= contention observee, sans donnee sensible.
        List<String> warnings = contentionLinesFor(output, accountId);
        assertThat(warnings).as("un seul WARN %s pour ce compte", EVENT).hasSize(1);
        String line = warnings.get(0);
        assertThat(line).contains("WARN").contains(EVENT).contains(accountId.toString());
        Matcher matcher = WAIT_MS.matcher(line);
        assertThat(matcher.find()).as("duree annoncee dans le log").isTrue();
        long announcedMillis = Long.parseLong(matcher.group(1));
        assertThat(announcedMillis).isGreaterThanOrEqualTo(THRESHOLD_MILLIS).isGreaterThanOrEqualTo(holdMillis);
        assertThat(line).doesNotContain(TEST_PASSWORD).doesNotContain(email);
        assertThat(output.getAll()).doesNotContain(TEST_PASSWORD).doesNotContain(email);

        // Requete de verrou reellement emise par Hibernate sur PostgreSQL (constat, pas suppose).
        List<String> rowLockStatements = output.getAll().lines()
                .filter(l -> HIBERNATE_ROW_LOCK_SQL.matcher(l).matches())
                .toList();
        // Constat PostgreSQL 16 / Hibernate 6.5.3 : "for update" (et non "for no key update"). Cette
        // requete est emise APRES le UPDATE de resetFailedAttempts (deja bloque/acquis ci-dessus) : sur
        // le chemin mot de passe correct, le verrou de ligne est donc deja detenu quand lock() s'execute.
        assertThat(rowLockStatements)
                .as("entityManager.lock(PESSIMISTIC_WRITE) emet SELECT ... FOR UPDATE sur PostgreSQL")
                .isNotEmpty()
                .allSatisfy(sql -> assertThat(sql.toLowerCase())
                        .contains("select id from auth_account where id=? for update")
                        .doesNotContain("no key"));
    }

    @Test
    void login_withoutContention_neverLogsContentionWarning(CapturedOutput output) {
        String warmupEmail = "pg-lock-warmup-" + UUID.randomUUID() + "@tontiflow.test";
        authAccountService.createAccount(warmupEmail, TEST_PASSWORD);
        authAccountService.login(warmupEmail, TEST_PASSWORD);

        String email = "pg-lock-none-" + UUID.randomUUID() + "@tontiflow.test";
        authAccountService.createAccount(email, TEST_PASSWORD);
        UUID accountId = authAccountRepository.findByEmail(email).orElseThrow().getId();

        assertThat(authAccountService.login(email, TEST_PASSWORD).refreshToken()).isNotNull();

        assertThat(contentionLinesFor(output, accountId)).isEmpty();
    }

    private static List<String> contentionLinesFor(CapturedOutput output, UUID accountId) {
        return output.getAll().lines()
                .filter(l -> l.contains(EVENT) && l.contains(accountId.toString()))
                .toList();
    }

    private record Blocked(int pid, String waitEventType, String query, List<Integer> blockers) {
    }

    private static Blocked blockedBy(Connection observer, int blockerPid) throws Exception {
        try (PreparedStatement ps = observer.prepareStatement(
                "SELECT pid, wait_event_type, query, pg_blocking_pids(pid) AS blockers "
                        + "FROM pg_stat_activity WHERE datname = current_database() "
                        + "AND ? = ANY(pg_blocking_pids(pid))")) {
            ps.setInt(1, blockerPid);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                Integer[] blockers = (Integer[]) rs.getArray("blockers").getArray();
                return new Blocked(rs.getInt("pid"), rs.getString("wait_event_type"), rs.getString("query"),
                        Arrays.asList(blockers));
            }
        }
    }
}
