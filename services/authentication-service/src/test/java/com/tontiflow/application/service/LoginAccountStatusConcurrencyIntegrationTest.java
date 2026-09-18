package com.tontiflow.application.service;

import com.tontiflow.application.exception.AccountDisabledException;
import com.tontiflow.application.exception.AccountLockedException;
import com.tontiflow.domain.enums.AccountStatus;
import com.tontiflow.domain.model.AuthAccount;
import com.tontiflow.domain.model.RefreshToken;
import com.tontiflow.infrastructure.repository.AuthAccountRepository;
import com.tontiflow.infrastructure.repository.RefreshTokenRepository;
import com.tontiflow.infrastructure.security.jwt.JwtTestSecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * Test de concurrence RÉELLE (vrais threads, H2 réel) de la fenêtre de
 * course entre {@code /login} et le changement administratif de statut
 * (décision R21-RD-FU, corrige le résidu documenté à la clôture de R21-RD).
 *
 * <p>Même patron ("ready/start latch", vrais threads, {@code
 * @SpringBootTest(webEnvironment = NONE)}) que {@link
 * AccountLockoutConcurrencyIntegrationTest} et {@link
 * AccountStatusConcurrencyIntegrationTest}. Les deux tests {@code
 * *LockAcquiredFirst*} utilisent en plus une <b>connexion JDBC brute</b>
 * (même {@link DataSource} que Spring, base H2 nommée partagée — {@code
 * DB_CLOSE_DELAY=-1}) pour tenir explicitement le verrou de ligne {@code
 * auth_account} et forcer, de façon déterministe et non fondée sur un
 * minutage, chacun des deux ordres de concurrence possibles — plutôt que
 * d'espérer les observer par hasard sous un simple test de charge.</p>
 *
 * <p><b>Limite explicitement documentée</b> : ces tests s'exécutent contre H2
 * (configuration {@code application-test.yml}), pas contre un PostgreSQL réel
 * — même limite déjà documentée pour tous les tests de concurrence de ce
 * dépôt. Le verrouillage de ligne ({@code SELECT ... FOR UPDATE}) est une
 * primitive SQL standard supportée par les deux moteurs, mais son exécution
 * réelle n'est prouvée ici que sous H2 — la garantie sous PostgreSQL reste
 * une inférence fondée sur la sémantique standard READ COMMITTED.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(JwtTestSecurityConfiguration.class)
class LoginAccountStatusConcurrencyIntegrationTest {

    private static final String TEST_PASSWORD = "S3cur3-Test-Passw0rd!";

    @Autowired
    private AuthAccountService authAccountService;

    @Autowired
    private AuthAccountRepository authAccountRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private DataSource dataSource;

    // ------------------------------------------------------------------
    // Non-concurrent (sanite) : login() sur un compte deja ACTIVE/LOCKED/DISABLED.
    // ------------------------------------------------------------------

    @Test
    void login_activeAccount_succeeds_andPersistsAnActiveRefreshTokenFamily() {
        String email = "login-fu-active@tontiflow.test";
        authAccountService.createAccount(email, TEST_PASSWORD);
        UUID accountId = authAccountRepository.findByEmail(email).orElseThrow().getId();

        AuthAccountService.LoginResult result = authAccountService.login(email, TEST_PASSWORD);

        assertThat(result.refreshToken().getRawToken()).isNotBlank();
        List<RefreshToken> families = activeFamiliesOf(accountId);
        assertThat(families).hasSize(1);
    }

    @Test
    void login_lockedAccount_throwsAndCreatesNoRefreshTokenFamily() {
        String email = "login-fu-locked@tontiflow.test";
        authAccountService.createAccount(email, TEST_PASSWORD);
        UUID accountId = authAccountRepository.findByEmail(email).orElseThrow().getId();
        authAccountService.changeAccountStatus(accountId, AccountStatus.LOCKED, "Preparation", UUID.randomUUID());

        assertThatThrownBy(() -> authAccountService.login(email, TEST_PASSWORD))
                .isInstanceOf(AccountLockedException.class);

        assertThat(activeFamiliesOf(accountId)).isEmpty();
    }

    @Test
    void login_disabledAccount_throwsAndCreatesNoRefreshTokenFamily() {
        String email = "login-fu-disabled@tontiflow.test";
        authAccountService.createAccount(email, TEST_PASSWORD);
        UUID accountId = authAccountRepository.findByEmail(email).orElseThrow().getId();
        authAccountService.changeAccountStatus(accountId, AccountStatus.DISABLED, "Preparation", UUID.randomUUID());

        assertThatThrownBy(() -> authAccountService.login(email, TEST_PASSWORD))
                .isInstanceOf(AccountDisabledException.class);

        assertThat(activeFamiliesOf(accountId)).isEmpty();
    }

    // ------------------------------------------------------------------
    // Ordre 1 (deterministe, JDBC brut) : l'administration acquiert le
    // verrou de ligne EN PREMIER. login() doit reellement bloquer, puis,
    // une fois le verrou relache (statut LOCKED committe), relire ce statut
    // frais et rejeter - sans jamais emettre de famille.
    // ------------------------------------------------------------------

    @Test
    void login_blocksWhileAdminHoldsRowLockFirst_thenRejectsOnFreshlyReadLockedStatus() throws Exception {
        String email = "login-fu-lockorder-admin-first@tontiflow.test";
        authAccountService.createAccount(email, TEST_PASSWORD);
        UUID accountId = authAccountRepository.findByEmail(email).orElseThrow().getId();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection lockHolder = dataSource.getConnection()) {
            lockHolder.setAutoCommit(false);
            try (PreparedStatement lock = lockHolder.prepareStatement(
                    "SELECT status FROM auth_account WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, accountId);
                lock.executeQuery();
            }

            Future<AuthAccountService.LoginResult> loginFuture =
                    executor.submit(() -> authAccountService.login(email, TEST_PASSWORD));

            // Preuve du blocage reel (pas suppose) : login() ne peut pas terminer tant
            // que la connexion brute ci-dessus tient le verrou de ligne.
            assertThatThrownBy(() -> loginFuture.get(500, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            // Simule la transition administrative reelle (ecriture du statut) puis relache
            // le verrou en committant.
            try (PreparedStatement updateStatus = lockHolder.prepareStatement(
                    "UPDATE auth_account SET status = 'LOCKED' WHERE id = ?")) {
                updateStatus.setObject(1, accountId);
                updateStatus.executeUpdate();
            }
            lockHolder.commit();

            try {
                loginFuture.get(5, TimeUnit.SECONDS);
                fail("login() aurait du lever AccountLockedException apres relecture du statut frais");
            } catch (ExecutionException e) {
                assertThat(e.getCause()).isInstanceOf(AccountLockedException.class);
            }
        } finally {
            executor.shutdown();
        }

        assertThat(activeFamiliesOf(accountId)).isEmpty();
    }

    // ------------------------------------------------------------------
    // Ordre 2 (deterministe, JDBC brut) : login() acquiert le verrou de
    // ligne EN PREMIER, insere sa famille de refresh token (simulee via SQL
    // brut, forme identique a celle produite par RefreshTokenService.issue)
    // PUIS relache le verrou. changeAccountStatus, bloque entre-temps,
    // procede alors et doit rattraper cette famille lors de sa revocation.
    // ------------------------------------------------------------------

    @Test
    void adminChangeAccountStatus_catchesRefreshTokenFamilyCommittedWhileLoginHeldTheRowLockFirst() throws Exception {
        String email = "login-fu-lockorder-login-first@tontiflow.test";
        authAccountService.createAccount(email, TEST_PASSWORD);
        UUID accountId = authAccountRepository.findByEmail(email).orElseThrow().getId();

        UUID simulatedTokenId = UUID.randomUUID();
        UUID simulatedFamilyId = UUID.randomUUID();
        Instant now = Instant.now();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection lockHolder = dataSource.getConnection()) {
            lockHolder.setAutoCommit(false);
            try (PreparedStatement lock = lockHolder.prepareStatement(
                    "SELECT status FROM auth_account WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, accountId);
                lock.executeQuery();
            }

            Future<AuthAccount> adminFuture = executor.submit(() ->
                    authAccountService.changeAccountStatus(accountId, AccountStatus.LOCKED, "Course simulee", UUID.randomUUID()));

            // Preuve du blocage reel : changeAccountStatus ne peut pas proceder tant que
            // la connexion brute tient le verrou (transitionStatusIfAllowed est lui-meme
            // un UPDATE, donc contend pour le meme verrou de ligne).
            assertThatThrownBy(() -> adminFuture.get(500, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            // Simule login() qui detient deja le verrou : insere directement la ligne que
            // RefreshTokenService.issue() aurait creee, PUIS commite (relache le verrou) -
            // equivalent au commit reel de la transaction login().
            try (PreparedStatement insert = lockHolder.prepareStatement(
                    "INSERT INTO refresh_token (id, account_id, token_hash, family_id, issued_at, expires_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?)")) {
                insert.setObject(1, simulatedTokenId);
                insert.setObject(2, accountId);
                insert.setString(3, "simulated-hash-" + simulatedTokenId);
                insert.setObject(4, simulatedFamilyId);
                insert.setTimestamp(5, Timestamp.from(now));
                insert.setTimestamp(6, Timestamp.from(now.plusSeconds(3600)));
                insert.executeUpdate();
            }
            lockHolder.commit();

            // Debloque : la revocation administrative doit desormais rattraper cette
            // famille, deja committee au moment ou changeAccountStatus procede reellement.
            adminFuture.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdown();
        }

        RefreshToken persisted = refreshTokenRepository.findById(simulatedTokenId).orElseThrow();
        assertThat(persisted.getRevokedAt()).isNotNull();
    }

    // ------------------------------------------------------------------
    // Stress concurrent reel (sans controle explicite de l'ordre) : invariant
    // verifie apres achevement des DEUX operations, quel que soit l'ordre
    // d'entrelacement reel - complementaire aux deux preuves deterministes
    // ci-dessus, meme esprit que AccountStatusConcurrencyIntegrationTest.
    // ------------------------------------------------------------------

    @Test
    void login_concurrentStressAgainstAdminLock_neverLeavesActiveFamilyForLockedAccount() throws Exception {
        runConcurrentLoginVersusStatusChange(AccountStatus.LOCKED, 15);
    }

    @Test
    void login_concurrentStressAgainstAdminDisable_neverLeavesActiveFamilyForDisabledAccount() throws Exception {
        runConcurrentLoginVersusStatusChange(AccountStatus.DISABLED, 15);
    }

    private void runConcurrentLoginVersusStatusChange(AccountStatus target, int trials) throws Exception {
        for (int trial = 0; trial < trials; trial++) {
            String email = "login-fu-stress-" + target + "-" + trial + "@tontiflow.test";
            authAccountService.createAccount(email, TEST_PASSWORD);
            UUID accountId = authAccountRepository.findByEmail(email).orElseThrow().getId();

            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch readyLatch = new CountDownLatch(2);
            CountDownLatch startLatch = new CountDownLatch(1);

            Callable<Void> loginTask = () -> {
                readyLatch.countDown();
                startLatch.await(5, TimeUnit.SECONDS);
                try {
                    authAccountService.login(email, TEST_PASSWORD);
                } catch (AccountLockedException | AccountDisabledException expected) {
                    // issue possible : le compte a deja bascule au moment de la relecture verrouillee.
                }
                return null;
            };
            Callable<Void> adminTask = () -> {
                readyLatch.countDown();
                startLatch.await(5, TimeUnit.SECONDS);
                authAccountService.changeAccountStatus(accountId, target, "Stress concurrent", UUID.randomUUID());
                return null;
            };

            List<Future<Void>> futures = executor.invokeAll(List.of(loginTask, adminTask));
            readyLatch.await(5, TimeUnit.SECONDS);
            startLatch.countDown();
            for (Future<Void> future : futures) {
                future.get(10, TimeUnit.SECONDS); // aucune exception inattendue ne doit fuiter
            }
            executor.shutdown();

            // Invariant valable quel que soit l'ordre reel d'entrelacement : une fois les
            // deux operations achevees, le compte est dans l'etat cible et AUCUNE famille
            // active ne subsiste.
            AuthAccount reloaded = authAccountRepository.findById(accountId).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(target);
            assertThat(activeFamiliesOf(accountId)).isEmpty();
        }
    }

    private List<RefreshToken> activeFamiliesOf(UUID accountId) {
        return refreshTokenRepository.findAll().stream()
                .filter(t -> t.getAccountId().equals(accountId) && t.getRevokedAt() == null)
                .toList();
    }
}
