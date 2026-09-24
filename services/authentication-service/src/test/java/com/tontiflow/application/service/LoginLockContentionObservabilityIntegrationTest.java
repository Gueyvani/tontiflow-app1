package com.tontiflow.application.service;

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

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
 * Preuve deterministe de l'observabilite de la contention du verrou de compte
 * (decision TICKET-2, Option 1) : WARN {@code login_lock_contention} emis par
 * {@link AuthAccountService#login} lorsque l'acquisition effective du verrou de
 * ligne ({@code resetFailedAttempts} + {@code lock()}) atteint 200 ms.
 *
 * <p><b>Contention reelle, prouvee par l'etat du moteur</b> : la transaction A
 * (JDBC brut) tient {@code SELECT ... FOR UPDATE} sur la ligne {@code
 * auth_account}. La transaction B execute un vrai {@code login()} (mot de
 * passe correct). Le blocage de B est observe dans H2 2.2.224
 * ({@code INFORMATION_SCHEMA.SESSIONS}: {@code BLOCKER_ID} = session de A,
 * {@code EXECUTING_STATEMENT} = {@code UPDATE auth_account}, i.e.
 * {@code resetFailedAttempts}) - jamais par {@code future.get(timeout)}. Le
 * BCrypt de B se deroule AVANT ce point et n'entre pas dans la mesure. A n'est
 * liberee qu'apres avoir observe B bloquee de facon continue pendant plus que
 * le seuil : chaque interrogation du moteur confirme l'etat BLOCKED, il n'y a
 * aucun {@code Thread.sleep()} ni delai suppose.</p>
 *
 * <p><b>Limite</b> : H2 uniquement (aucun PostgreSQL reel dans ce depot) ; la vue
 * {@code SESSIONS} est specifique a H2 ({@code pg_stat_activity} /
 * {@code pg_blocking_pids()} en PostgreSQL).</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(JwtTestSecurityConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class LoginLockContentionObservabilityIntegrationTest {

    private static final String TEST_PASSWORD = "S3cur3-Test-Passw0rd!";
    private static final String EVENT = "login_lock_contention";
    private static final long THRESHOLD_MILLIS = 200;
    /** Duree pendant laquelle B doit etre observee BLOCKED en continu avant liberation de A (> seuil, avec marge). */
    private static final long HOLD_WHILE_OBSERVED_BLOCKED_NANOS = TimeUnit.MILLISECONDS.toNanos(THRESHOLD_MILLIS + 100);
    private static final long OBSERVATION_DEADLINE_NANOS = TimeUnit.SECONDS.toNanos(20);
    private static final long POLL_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(5);
    private static final Pattern WAIT_MS = Pattern.compile("attente d'acquisition du verrou de compte (\\d+) ms");

    @Autowired
    private AuthAccountService authAccountService;

    @Autowired
    private AuthAccountRepository authAccountRepository;

    @Autowired
    private DataSource dataSource;

    @Test
    void login_blockedOnRowLockAboveThreshold_logsSingleContentionWarning(CapturedOutput output) throws Exception {
        String email = "lock-observability-" + UUID.randomUUID() + "@tontiflow.test";
        authAccountService.createAccount(email, TEST_PASSWORD);
        UUID accountId = authAccountRepository.findByEmail(email).orElseThrow().getId();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection transactionA = dataSource.getConnection();
             Connection observer = dataSource.getConnection()) {

            // A : detient le verrou de ligne (SELECT ... FOR UPDATE), transaction ouverte.
            transactionA.setAutoCommit(false);
            long sessionIdOfA;
            try (PreparedStatement ps = transactionA.prepareStatement("SELECT SESSION_ID()");
                 ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                sessionIdOfA = rs.getLong(1);
            }
            try (PreparedStatement ps = transactionA.prepareStatement(
                    "SELECT id FROM auth_account WHERE id = ? FOR UPDATE")) {
                ps.setObject(1, accountId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).as("A detient bien la ligne").isTrue();
                }
            }

            // B : vrai login() (mot de passe correct), thread distinct.
            Future<AuthAccountService.LoginResult> loginB =
                    executor.submit(() -> authAccountService.login(email, TEST_PASSWORD));

            // Observation explicite : B BLOCKED par A, en continu pendant plus que le seuil.
            long firstBlockedNanos = -1;
            String blockedStatement = null;
            long deadline = System.nanoTime() + OBSERVATION_DEADLINE_NANOS;
            while (System.nanoTime() < deadline) {
                String statement = blockedStatementBy(observer, sessionIdOfA);
                long now = System.nanoTime();
                if (statement != null) {
                    if (firstBlockedNanos < 0) {
                        firstBlockedNanos = now;
                        blockedStatement = statement;
                    }
                    if (now - firstBlockedNanos >= HOLD_WHILE_OBSERVED_BLOCKED_NANOS) {
                        break;
                    }
                } else if (firstBlockedNanos >= 0) {
                    throw new AssertionError("B a cesse d'etre bloquee avant la liberation de A");
                }
                LockSupport.parkNanos(POLL_INTERVAL_NANOS); // intervalle de sondage uniquement
            }
            assertThat(blockedStatement).as("B doit etre observee BLOCKED par A").isNotNull();
            assertThat(blockedStatement.toLowerCase())
                    .as("resetFailedAttempts (UPDATE) est l'instruction qui attend le verrou de ligne")
                    .contains("update auth_account");
            assertThat(loginB.isDone()).isFalse();
            assertThat(System.nanoTime() - firstBlockedNanos)
                    .as("B observee bloquee plus longtemps que le seuil").isGreaterThanOrEqualTo(HOLD_WHILE_OBSERVED_BLOCKED_NANOS);

            // Liberation de A uniquement apres cette observation.
            transactionA.commit();
            assertThat(loginB.get(20, TimeUnit.SECONDS).refreshToken()).isNotNull();
        } finally {
            executor.shutdownNow();
        }

        List<String> warnings = contentionLinesFor(output, accountId);
        assertThat(warnings).as("un seul WARN %s pour ce compte", EVENT).hasSize(1);
        String line = warnings.get(0);
        assertThat(line).contains("WARN").contains(EVENT);
        Matcher matcher = WAIT_MS.matcher(line);
        assertThat(matcher.find()).as("duree annoncee dans le log").isTrue();
        assertThat(Long.parseLong(matcher.group(1))).isGreaterThanOrEqualTo(THRESHOLD_MILLIS);
        // Aucune donnee sensible.
        assertThat(line).doesNotContain(TEST_PASSWORD).doesNotContain(email);
        assertThat(output.getAll()).doesNotContain(TEST_PASSWORD).doesNotContain(email);
    }

    // Sous le seuil : aucune contention. Un login prealable sur un autre compte "rechauffe" le
    // chemin (classes, requetes) pour qu'un cout de premier appel ne franchisse pas 200 ms ; le
    // test ne repose sur aucune attente, seulement sur l'absence du WARN pour CE compte.
    @Test
    void login_withoutContention_neverLogsContentionWarning(CapturedOutput output) {
        String warmupEmail = "lock-observability-warmup-" + UUID.randomUUID() + "@tontiflow.test";
        authAccountService.createAccount(warmupEmail, TEST_PASSWORD);
        authAccountService.login(warmupEmail, TEST_PASSWORD);

        String email = "lock-observability-none-" + UUID.randomUUID() + "@tontiflow.test";
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

    private static String blockedStatementBy(Connection observer, long blockerSessionId) throws Exception {
        try (PreparedStatement ps = observer.prepareStatement(
                "SELECT EXECUTING_STATEMENT FROM INFORMATION_SCHEMA.SESSIONS WHERE BLOCKER_ID = ?")) {
            ps.setLong(1, blockerSessionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }
}
