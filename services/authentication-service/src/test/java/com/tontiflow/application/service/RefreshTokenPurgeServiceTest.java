package com.tontiflow.application.service;

import com.tontiflow.domain.model.RefreshToken;
import com.tontiflow.infrastructure.repository.RefreshTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires de {@link RefreshTokenPurgeService} (décision F-2) : cutoff, marge, lots, curseur,
 * idempotence, validation de configuration. Horloge fixe, repository simulé : aucune durée réelle.
 * La sémantique SQL (familles entièrement expirées) est prouvée par {@code RefreshTokenPurgeRepositoryTest}
 * (H2) et {@code RefreshTokenPurgePostgresIntegrationTest} (PostgreSQL).
 */
class RefreshTokenPurgeServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");
    private static final int BATCH_SIZE = 2;

    private final RefreshTokenRepository repository = mock(RefreshTokenRepository.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private RefreshTokenPurgeService service;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new RefreshTokenPurgeService(repository, clock, transactionManager, "10m", BATCH_SIZE);
    }

    private static RefreshToken token(Instant expiresAt) {
        RefreshToken token = new RefreshToken();
        token.setId(UUID.randomUUID());
        token.setExpiresAt(expiresAt);
        return token;
    }

    @Test
    void noCandidate_deletesNothing_andReportsZero() {
        when(repository.findPurgeableBatch(any(), any(), any(), anyInt())).thenReturn(List.of());

        RefreshTokenPurgeService.PurgeResult result = service.purgeExpiredFamilies();

        assertThat(result.deletedRows()).isZero();
        assertThat(result.batches()).isZero();
        verify(repository, never()).deleteExpiredByIds(any(), any());
    }

    @Test
    void cutoffIsNowMinusGrace_usingTheInjectedClock() {
        when(repository.findPurgeableBatch(any(), any(), any(), anyInt())).thenReturn(List.of());

        service.purgeExpiredFamilies();

        verify(repository).findPurgeableBatch(eq(NOW.minusSeconds(600)), eq(Instant.EPOCH),
                eq(new UUID(0L, 0L)), eq(BATCH_SIZE));
    }

    @Test
    void partialBatch_isDeletedWithCutoffGuard_andEndsThePass() {
        RefreshToken a = token(NOW.minusSeconds(86_400));
        when(repository.findPurgeableBatch(any(), any(), any(), anyInt())).thenReturn(List.of(a));
        when(repository.deleteExpiredByIds(any(), any())).thenReturn(1);

        RefreshTokenPurgeService.PurgeResult result = service.purgeExpiredFamilies();

        assertThat(result.deletedRows()).isEqualTo(1);
        assertThat(result.batches()).isEqualTo(1);
        verify(repository).deleteExpiredByIds(eq(List.of(a.getId())), eq(NOW.minusSeconds(600)));
        // Un lot partiel signifie qu'il n'y a plus de candidat : une seule lecture.
        verify(repository, times(1)).findPurgeableBatch(any(), any(), any(), anyInt());
    }

    @Test
    void fullBatches_continueWithTheCursorOfTheLastRow_untilAPartialBatch() {
        RefreshToken a = token(NOW.minusSeconds(300_000));
        RefreshToken b = token(NOW.minusSeconds(200_000));
        RefreshToken c = token(NOW.minusSeconds(100_000));
        when(repository.findPurgeableBatch(any(), any(), any(), anyInt()))
                .thenReturn(List.of(a, b))
                .thenReturn(List.of(c));
        when(repository.deleteExpiredByIds(any(), any())).thenAnswer(invocation ->
                ((Collection<?>) invocation.getArgument(0)).size());

        RefreshTokenPurgeService.PurgeResult result = service.purgeExpiredFamilies();

        assertThat(result.deletedRows()).isEqualTo(3);
        assertThat(result.batches()).isEqualTo(2);
        ArgumentCaptor<Instant> afterExpires = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<UUID> afterId = ArgumentCaptor.forClass(UUID.class);
        verify(repository, times(2)).findPurgeableBatch(any(), afterExpires.capture(), afterId.capture(), eq(BATCH_SIZE));
        assertThat(afterExpires.getAllValues()).containsExactly(Instant.EPOCH, b.getExpiresAt());
        assertThat(afterId.getAllValues()).containsExactly(new UUID(0L, 0L), b.getId());
    }

    @Test
    void fullBatchFollowedByAnEmptyOne_stopsAfterTheEmptyRead() {
        RefreshToken a = token(NOW.minusSeconds(200_000));
        RefreshToken b = token(NOW.minusSeconds(100_000));
        when(repository.findPurgeableBatch(any(), any(), any(), anyInt()))
                .thenReturn(List.of(a, b))
                .thenReturn(List.of());
        when(repository.deleteExpiredByIds(any(), any())).thenReturn(2);

        RefreshTokenPurgeService.PurgeResult result = service.purgeExpiredFamilies();

        assertThat(result.deletedRows()).isEqualTo(2);
        assertThat(result.batches()).isEqualTo(1);
        verify(repository, times(2)).findPurgeableBatch(any(), any(), any(), anyInt());
    }

    @Test
    void repeatedPurge_isIdempotent_secondPassFindsNothing() {
        RefreshToken a = token(NOW.minusSeconds(86_400));
        when(repository.findPurgeableBatch(any(), any(), any(), anyInt()))
                .thenReturn(List.of(a))
                .thenReturn(List.of());
        when(repository.deleteExpiredByIds(any(), any())).thenReturn(1);

        RefreshTokenPurgeService.PurgeResult first = service.purgeExpiredFamilies();
        RefreshTokenPurgeService.PurgeResult second = service.purgeExpiredFamilies();

        assertThat(first.deletedRows()).isEqualTo(1);
        assertThat(second.deletedRows()).isZero();
        verify(repository, times(1)).deleteExpiredByIds(any(), any());
    }

    @Test
    void rowsAlreadyDeletedByAnotherInstance_areNotAnError() {
        RefreshToken a = token(NOW.minusSeconds(86_400));
        when(repository.findPurgeableBatch(any(), any(), any(), anyInt())).thenReturn(List.of(a));
        // Une autre instance a deja supprime la ligne : le DELETE n'affecte rien.
        when(repository.deleteExpiredByIds(any(), any())).thenReturn(0);

        RefreshTokenPurgeService.PurgeResult result = service.purgeExpiredFamilies();

        assertThat(result.deletedRows()).isZero();
        assertThat(result.batches()).isEqualTo(1);
    }

    @Test
    void invalidConfiguration_failsFast() {
        assertThatThrownBy(() -> new RefreshTokenPurgeService(repository, clock, transactionManager, "-1m", 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RefreshTokenPurgeService(repository, clock, transactionManager, "10m", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zeroGrace_isAllowed_andCutoffIsNow() {
        RefreshTokenPurgeService noGrace = new RefreshTokenPurgeService(repository, clock, transactionManager, "0s", 5);
        when(repository.findPurgeableBatch(any(), any(), any(), anyInt())).thenReturn(List.of());

        noGrace.purgeExpiredFamilies();

        verify(repository).findPurgeableBatch(eq(NOW), any(), any(), eq(5));
    }
}
