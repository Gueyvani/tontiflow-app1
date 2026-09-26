package com.tontiflow.infrastructure.repository;

import com.tontiflow.domain.model.RefreshToken;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sémantique SQL de la purge (décision F-2) sur H2 : une ligne n'est purgeable que si sa famille est
 * entièrement expirée avant le cutoff. Les scénarios portent sur des familles distinctes, identifiées
 * par leurs jetons, pour ne dépendre d'aucune autre donnée de la base partagée.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RefreshTokenPurgeRepositoryTest {

    private static final Instant CUTOFF = Instant.parse("2026-06-01T12:00:00Z");
    private static final Instant PAST = CUTOFF.minusSeconds(86_400);
    private static final Instant FUTURE = CUTOFF.plusSeconds(86_400);

    @Autowired
    private RefreshTokenRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    private RefreshToken save(UUID family, Instant expiresAt, Instant revokedAt) {
        RefreshToken token = new RefreshToken();
        token.setAccountId(UUID.randomUUID());
        token.setFamilyId(family);
        token.setTokenHash(UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""));
        token.setIssuedAt(expiresAt.minusSeconds(3600));
        token.setExpiresAt(expiresAt);
        token.setRevokedAt(revokedAt);
        RefreshToken saved = repository.save(token);
        entityManager.flush();
        return saved;
    }

    private Set<UUID> purgeableIds() {
        return repository.findPurgeableBatch(CUTOFF, Instant.EPOCH, new UUID(0L, 0L), 1000).stream()
                .map(RefreshToken::getId)
                .collect(Collectors.toSet());
    }

    @Test
    void activeFamily_isNeverPurgeable() {
        RefreshToken active = save(UUID.randomUUID(), FUTURE, null);

        assertThat(purgeableIds()).doesNotContain(active.getId());
    }

    @Test
    void revokedButNotExpiredToken_isKept_forReuseDetection() {
        RefreshToken consumed = save(UUID.randomUUID(), FUTURE, PAST);

        assertThat(purgeableIds()).doesNotContain(consumed.getId());
    }

    @Test
    void expiredTokenWithALiveSuccessorInItsFamily_isKept() {
        UUID family = UUID.randomUUID();
        RefreshToken oldExpired = save(family, PAST, PAST);
        RefreshToken liveSuccessor = save(family, FUTURE, null);

        assertThat(purgeableIds()).doesNotContain(oldExpired.getId(), liveSuccessor.getId());
    }

    @Test
    void fullyExpiredFamily_isPurgeable_wholeFamily() {
        UUID family = UUID.randomUUID();
        RefreshToken first = save(family, PAST.minusSeconds(100), PAST);
        RefreshToken second = save(family, PAST, PAST);
        RefreshToken third = save(family, PAST.plusSeconds(10), null);

        assertThat(purgeableIds()).contains(first.getId(), second.getId(), third.getId());
    }

    @Test
    void marginBoundary_tokenExpiringExactlyAtCutoffKeepsItsFamily_oneSecondBeforeDoesNot() {
        UUID keptFamily = UUID.randomUUID();
        RefreshToken keptOld = save(keptFamily, PAST, PAST);
        save(keptFamily, CUTOFF, null); // expires_at >= cutoff : encore conservable

        UUID purgedFamily = UUID.randomUUID();
        RefreshToken purgedOld = save(purgedFamily, PAST, PAST);
        RefreshToken purgedHead = save(purgedFamily, CUTOFF.minusSeconds(1), null);

        Set<UUID> purgeable = purgeableIds();
        assertThat(purgeable).doesNotContain(keptOld.getId());
        assertThat(purgeable).contains(purgedOld.getId(), purgedHead.getId());
    }

    @Test
    void cursorPagination_visitsEveryPurgeableRowOnce_inOrder_andSkipsKeptOnes() {
        List<RefreshToken> dead = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            dead.add(save(UUID.randomUUID(), PAST.minusSeconds(1_000 - i), PAST));
        }
        UUID liveFamily = UUID.randomUUID();
        RefreshToken keptOld = save(liveFamily, PAST.minusSeconds(2_000), PAST);
        save(liveFamily, FUTURE, null);

        List<UUID> visited = new ArrayList<>();
        Instant afterExpires = Instant.EPOCH;
        UUID afterId = new UUID(0L, 0L);
        int guard = 0;
        while (guard++ < 50) {
            List<RefreshToken> batch = repository.findPurgeableBatch(CUTOFF, afterExpires, afterId, 2);
            batch.forEach(t -> visited.add(t.getId()));
            if (batch.size() < 2) {
                break;
            }
            RefreshToken last = batch.get(batch.size() - 1);
            afterExpires = last.getExpiresAt();
            afterId = last.getId();
        }

        assertThat(visited).doesNotHaveDuplicates();
        assertThat(visited).containsAll(dead.stream().map(RefreshToken::getId).toList());
        assertThat(visited).doesNotContain(keptOld.getId());
    }

    @Test
    void deleteExpiredByIds_deletesOnlyRowsExpiredBeforeCutoff_neverALiveOne() {
        RefreshToken expired = save(UUID.randomUUID(), PAST, PAST);
        RefreshToken live = save(UUID.randomUUID(), FUTURE, null);

        int deleted = repository.deleteExpiredByIds(List.of(expired.getId(), live.getId()), CUTOFF);
        entityManager.flush();
        entityManager.clear();

        assertThat(deleted).isEqualTo(1);
        assertThat(repository.findById(expired.getId())).isEmpty();
        assertThat(repository.findById(live.getId())).isPresent();
    }

    @Test
    void deleteExpiredByIds_isIdempotent_forIdsAlreadyDeleted() {
        RefreshToken expired = save(UUID.randomUUID(), PAST, PAST);

        int first = repository.deleteExpiredByIds(List.of(expired.getId()), CUTOFF);
        int second = repository.deleteExpiredByIds(List.of(expired.getId()), CUTOFF);

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
    }
}
