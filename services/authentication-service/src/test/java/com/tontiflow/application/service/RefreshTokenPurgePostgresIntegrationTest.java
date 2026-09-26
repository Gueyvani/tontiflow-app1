package com.tontiflow.application.service;

import com.tontiflow.domain.model.AuthAccount;
import com.tontiflow.domain.model.RefreshToken;
import com.tontiflow.domain.enums.AccountStatus;
import com.tontiflow.infrastructure.repository.AuthAccountRepository;
import com.tontiflow.infrastructure.repository.RefreshTokenRepository;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Décision F-2 sur un vrai PostgreSQL (Testcontainers, postgres:16-alpine, Flyway V1..V6 + Hibernate
 * {@code validate}, comme en production) : requête de purge, {@code FOR UPDATE SKIP LOCKED},
 * suppression par lots, concurrence de deux purges, absence de suppression de familles conservables,
 * migration V6. Exécuté uniquement via le profil Maven {@code postgres-integration} (Docker requis).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(JwtTestSecurityConfiguration.class)
@Testcontainers
class RefreshTokenPurgePostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("auth_purge_test")
            .withUsername("tc_user")
            .withPassword("tc_password");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        // Le profil "test" (H2) desactive Flyway : ici, meme configuration qu'en production.
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    // Repartition du jeu de donnees (une ligne = un refresh_token) :
    private static final int DEAD_FAMILIES = 40;         // 3 lignes chacune, toutes expirees depuis 2 jours
    private static final int LIVE_WITH_OLD_FAMILIES = 15; // 1 ancienne ligne expiree + 1 tete valide
    private static final int ACTIVE_FAMILIES = 10;        // 1 ligne valide
    private static final int REVOKED_LIVE_FAMILIES = 5;   // 1 ligne revoquee non expiree
    private static final int GRACE_FAMILIES = 3;          // 1 ligne expiree depuis 5 min (< marge de 10 min)
    private static final int DEAD_ROWS = DEAD_FAMILIES * 3;
    private static final int KEPT_ROWS = LIVE_WITH_OLD_FAMILIES * 2 + ACTIVE_FAMILIES + REVOKED_LIVE_FAMILIES
            + GRACE_FAMILIES;

    @Autowired
    private AuthAccountRepository authAccountRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private Clock clock;

    @Autowired
    private RefreshTokenPurgeService defaultPurgeService;

    private JdbcTemplate jdbc;
    private UUID accountId;

    @BeforeEach
    void resetData() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM refresh_token");
        AuthAccount account = new AuthAccount();
        account.setEmail("purge-pg-" + UUID.randomUUID() + "@tontiflow.test");
        account.setPasswordHash("$2a$10$0123456789012345678901234567890123456789012345678901");
        account.setStatus(AccountStatus.ACTIVE);
        accountId = authAccountRepository.save(account).getId();
    }

    private RefreshToken row(UUID family, Instant expiresAt, Instant revokedAt) {
        RefreshToken token = new RefreshToken();
        token.setAccountId(accountId);
        token.setFamilyId(family);
        token.setTokenHash((UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "").substring(0, 64));
        token.setIssuedAt(expiresAt.minus(30, ChronoUnit.DAYS));
        token.setExpiresAt(expiresAt);
        token.setRevokedAt(revokedAt);
        return token;
    }

    private void seed() {
        Instant now = clock.instant();
        List<RefreshToken> rows = new ArrayList<>();
        Instant twoDaysAgo = now.minus(2, ChronoUnit.DAYS);
        for (int i = 0; i < DEAD_FAMILIES; i++) {
            UUID family = UUID.randomUUID();
            rows.add(row(family, twoDaysAgo, twoDaysAgo));
            rows.add(row(family, twoDaysAgo.plus(1, ChronoUnit.HOURS), twoDaysAgo.plus(1, ChronoUnit.HOURS)));
            rows.add(row(family, twoDaysAgo.plus(2, ChronoUnit.HOURS), null));
        }
        for (int i = 0; i < LIVE_WITH_OLD_FAMILIES; i++) {
            UUID family = UUID.randomUUID();
            rows.add(row(family, twoDaysAgo, twoDaysAgo));
            rows.add(row(family, now.plus(10, ChronoUnit.DAYS), null));
        }
        for (int i = 0; i < ACTIVE_FAMILIES; i++) {
            rows.add(row(UUID.randomUUID(), now.plus(20, ChronoUnit.DAYS), null));
        }
        for (int i = 0; i < REVOKED_LIVE_FAMILIES; i++) {
            rows.add(row(UUID.randomUUID(), now.plus(5, ChronoUnit.DAYS), now.minus(1, ChronoUnit.HOURS)));
        }
        for (int i = 0; i < GRACE_FAMILIES; i++) {
            rows.add(row(UUID.randomUUID(), now.minus(5, ChronoUnit.MINUTES), null));
        }
        refreshTokenRepository.saveAll(rows);
        assertThat(count()).isEqualTo(DEAD_ROWS + KEPT_ROWS);
    }

    private long count() {
        return jdbc.queryForObject("SELECT count(*) FROM refresh_token", Long.class);
    }

    @Test
    void migrationV6_createsTheExpiresAtIndex() {
        Integer indexes = jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE tablename = 'refresh_token' "
                        + "AND indexname = 'idx_refresh_token_expires_at'", Integer.class);

        assertThat(indexes).isEqualTo(1);
    }

    @Test
    void batchedPurge_deletesExactlyTheFullyExpiredFamilies_andKeepsEverythingElse() {
        seed();
        RefreshTokenPurgeService smallBatches =
                new RefreshTokenPurgeService(refreshTokenRepository, clock, transactionManager, "10m", 7);

        RefreshTokenPurgeService.PurgeResult result = smallBatches.purgeExpiredFamilies();

        assertThat(result.deletedRows()).isEqualTo(DEAD_ROWS);
        assertThat(result.batches()).isEqualTo((int) Math.ceil(DEAD_ROWS / 7.0)); // lots successifs de 7 max
        assertThat(count()).isEqualTo(KEPT_ROWS);
        // Chaque famille "vivante avec ancien jeton expire" a conserve ses 2 lignes.
        Integer familiesWithTwoRows = jdbc.queryForObject(
                "SELECT count(*) FROM (SELECT family_id FROM refresh_token GROUP BY family_id HAVING count(*) = 2) f",
                Integer.class);
        assertThat(familiesWithTwoRows).isEqualTo(LIVE_WITH_OLD_FAMILIES);
        // Aucune ligne expiree avant le cutoff ne subsiste hors des familles vivantes.
        Integer expiredWithoutLiveSibling = jdbc.queryForObject("""
                SELECT count(*) FROM refresh_token r
                WHERE r.expires_at < now() - interval '10 minutes'
                  AND NOT EXISTS (SELECT 1 FROM refresh_token s
                                  WHERE s.family_id = r.family_id AND s.expires_at >= now() - interval '10 minutes')
                """, Integer.class);
        assertThat(expiredWithoutLiveSibling).isZero();
    }

    @Test
    void repeatedPurge_isIdempotent_onPostgres() {
        seed();

        RefreshTokenPurgeService.PurgeResult first = defaultPurgeService.purgeExpiredFamilies();
        RefreshTokenPurgeService.PurgeResult second = defaultPurgeService.purgeExpiredFamilies();

        assertThat(first.deletedRows()).isEqualTo(DEAD_ROWS);
        assertThat(second.deletedRows()).isZero();
        assertThat(count()).isEqualTo(KEPT_ROWS);
    }

    @Test
    void twoConcurrentPurges_neverFail_neverDeleteAKeptRow_andDeleteEachDeadRowOnce() throws Exception {
        seed();
        RefreshTokenPurgeService first =
                new RefreshTokenPurgeService(refreshTokenRepository, clock, transactionManager, "10m", 5);
        RefreshTokenPurgeService second =
                new RefreshTokenPurgeService(refreshTokenRepository, clock, transactionManager, "10m", 5);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<RefreshTokenPurgeService.PurgeResult> a = pool.submit(() -> {
                start.await();
                return first.purgeExpiredFamilies();
            });
            Future<RefreshTokenPurgeService.PurgeResult> b = pool.submit(() -> {
                start.await();
                return second.purgeExpiredFamilies();
            });
            start.countDown();

            int deletedByA = a.get(60, TimeUnit.SECONDS).deletedRows();
            int deletedByB = b.get(60, TimeUnit.SECONDS).deletedRows();

            // Aucune exception (get() aurait echoue) ; chaque ligne morte est supprimee au plus une fois.
            assertThat(deletedByA + deletedByB).isLessThanOrEqualTo(DEAD_ROWS);
        } finally {
            pool.shutdownNow();
        }

        // Un passage supplementaire termine ce que SKIP LOCKED a pu laisser (lignes verrouillees par l'autre).
        int remainingDead = defaultPurgeService.purgeExpiredFamilies().deletedRows();
        assertThat(count()).isEqualTo(KEPT_ROWS);
        assertThat(remainingDead).isLessThanOrEqualTo(DEAD_ROWS);
    }

    @Test
    void rowsLockedByAnotherTransaction_areSkipped_notWaitedFor_andPurgedByTheNextPass() throws Exception {
        seed();
        // Une transaction externe verrouille tout de suite quelques lignes mortes (simule une autre instance).
        var lockingConnection = dataSource.getConnection();
        lockingConnection.setAutoCommit(false);
        try (var statement = lockingConnection.createStatement()) {
            // Uniquement des lignes de familles MORTES (les anciennes lignes des familles vivantes ne sont
            // pas purgeables et fausseraient le decompte attendu).
            statement.execute("""
                    SELECT id FROM refresh_token
                    WHERE family_id IN (SELECT family_id FROM refresh_token
                                        GROUP BY family_id HAVING max(expires_at) < now() - interval '1 day')
                    ORDER BY expires_at, id LIMIT 10 FOR UPDATE
                    """);

            long startNanos = System.nanoTime();
            RefreshTokenPurgeService.PurgeResult whileLocked = defaultPurgeService.purgeExpiredFamilies();
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

            // SKIP LOCKED : la purge ne bloque pas sur les 10 lignes verrouillees et supprime le reste.
            assertThat(whileLocked.deletedRows()).isEqualTo(DEAD_ROWS - 10);
            assertThat(elapsedMillis).as("la purge ne doit pas attendre le verrou").isLessThan(30_000);
        } finally {
            lockingConnection.rollback();
            lockingConnection.close();
        }

        assertThat(defaultPurgeService.purgeExpiredFamilies().deletedRows()).isEqualTo(10);
        assertThat(count()).isEqualTo(KEPT_ROWS);
    }

    @Test
    void expiresAtIndex_isUsableByThePurgeScan() {
        seed();
        jdbc.execute("ANALYZE refresh_token");

        List<String> plan = jdbc.execute((java.sql.Connection connection) -> {
            List<String> lines = new ArrayList<>();
            try (var statement = connection.createStatement()) {
                statement.execute("SET enable_seqscan = off");
                try (var resultSet = statement.executeQuery(
                        "EXPLAIN SELECT id FROM refresh_token WHERE expires_at < now() ORDER BY expires_at, id LIMIT 10")) {
                    while (resultSet.next()) {
                        lines.add(resultSet.getString(1));
                    }
                }
                statement.execute("RESET enable_seqscan");
            }
            return lines;
        });

        assertThat(String.join("\n", plan)).contains("idx_refresh_token_expires_at");
    }
}
