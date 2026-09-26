package com.tontiflow.application.service;

import com.tontiflow.domain.model.RefreshToken;
import com.tontiflow.infrastructure.repository.RefreshTokenRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Purge physique des Refresh Tokens (décision F-2) : supprime les <b>familles entièrement
 * expirées</b>, par lots, sans jamais toucher au comportement de sécurité du refresh.
 *
 * <p><b>Politique de rétention</b> (cutoff = maintenant − {@code refresh-token.purge.grace}) :</p>
 * <ul>
 *   <li>jeton actif ({@code expires_at >= cutoff}) : conservé ;</li>
 *   <li>jeton révoqué ou consommé mais non expiré : conservé (détection de réutilisation : un rejeu
 *       d'un jeton consommé n'est détecté que tant que sa ligne existe et n'est pas expirée, car
 *       {@code RefreshTokenService#rotate} refuse un jeton expiré avant toute consommation) ;</li>
 *   <li>jeton expiré dont la famille a encore un jeton non expiré : conservé ({@code /logout}
 *       avec cet ancien jeton doit continuer à révoquer le successeur encore valide) ;</li>
 *   <li>famille dont <b>tous</b> les jetons sont expirés depuis plus que la marge : purgeable.</li>
 * </ul>
 *
 * <p><b>Marge technique</b> : pas une règle de conformité. {@code rotate} vérifie l'expiration avec
 * l'horloge de l'instance qui traite la requête ; la marge (défaut 10 min) couvre le décalage
 * d'horloge entre instances et une rotation en cours autour de l'expiration, pour qu'un jeton
 * présenté « juste avant » son expiration ne voie jamais sa famille supprimée en même temps.
 * Même {@link Clock} que {@link RefreshTokenService} : temps logique identique, tests déterministes.</p>
 *
 * <p><b>Lots</b> : une transaction courte et indépendante par lot (lecture indexée puis
 * {@code DELETE} par clé primaire). Les lots déjà validés le restent si un lot suivant échoue ; la
 * passe suivante reprend (les lignes éligibles le restent). Un curseur {@code (expires_at, id)} évite
 * de relire, à chaque lot, les lignes expirées mais non purgeables d'une même passe.</p>
 *
 * <p><b>Idempotence / multi-instance</b> : aucune coordination requise. La sélection d'un lot
 * verrouille ses lignes ({@code FOR UPDATE SKIP LOCKED}) jusqu'au {@code DELETE} de la même
 * transaction : deux instances simultanées traitent des lots disjoints, sans attente ni
 * interblocage ; des lignes déjà supprimées par une autre passe ne provoquent aucune erreur. Une ligne
 * éligible ne peut plus devenir conservable (voir {@link RefreshTokenRepository#findPurgeableBatch}).
 * Cette classe ne doit pas être appelée depuis une transaction englobante : chaque lot ouvre la
 * sienne.</p>
 *
 * <p>Ne journalise jamais de jeton, de hash ni d'identifiant de compte.</p>
 */
@Service
public class RefreshTokenPurgeService {

    /** Résultat d'une passe de purge. */
    public record PurgeResult(int deletedRows, int batches) {
    }

    private record BatchOutcome(int selected, int deleted, Instant lastExpiresAt, UUID lastId) {
    }

    private final RefreshTokenRepository refreshTokenRepository;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;
    private final Duration grace;
    private final int batchSize;

    public RefreshTokenPurgeService(RefreshTokenRepository refreshTokenRepository, Clock clock,
                                     PlatformTransactionManager transactionManager,
                                     @Value("${refresh-token.purge.grace:10m}") String grace,
                                     @Value("${refresh-token.purge.batch-size:1000}") int batchSize) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        // Format simplifie ("10m", "1h") : meme convention que refresh-token.ttl.
        this.grace = DurationStyle.detectAndParse(grace);
        if (this.grace.isNegative()) {
            throw new IllegalArgumentException("refresh-token.purge.grace ne peut pas etre negatif");
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("refresh-token.purge.batch-size doit etre >= 1");
        }
        this.batchSize = batchSize;
    }

    /**
     * Exécute une passe complète : lots successifs jusqu'à ce qu'un lot ne soit plus plein.
     * Le {@code cutoff} est calculé une seule fois au début de la passe.
     */
    public PurgeResult purgeExpiredFamilies() {
        Instant cutoff = clock.instant().minus(grace);
        Instant afterExpiresAt = Instant.EPOCH;
        UUID afterId = new UUID(0L, 0L);
        int deletedRows = 0;
        int batches = 0;

        while (true) {
            final Instant cursorExpiresAt = afterExpiresAt;
            final UUID cursorId = afterId;
            BatchOutcome outcome = transactionTemplate.execute(status -> purgeOneBatch(cutoff, cursorExpiresAt, cursorId));

            if (outcome == null || outcome.selected() == 0) {
                break;
            }
            batches++;
            deletedRows += outcome.deleted();
            if (outcome.selected() < batchSize) {
                break;
            }
            afterExpiresAt = outcome.lastExpiresAt();
            afterId = outcome.lastId();
        }
        return new PurgeResult(deletedRows, batches);
    }

    private BatchOutcome purgeOneBatch(Instant cutoff, Instant afterExpiresAt, UUID afterId) {
        List<RefreshToken> batch = refreshTokenRepository.findPurgeableBatch(cutoff, afterExpiresAt, afterId, batchSize);
        if (batch.isEmpty()) {
            return new BatchOutcome(0, 0, afterExpiresAt, afterId);
        }
        List<UUID> ids = batch.stream().map(RefreshToken::getId).toList();
        int deleted = refreshTokenRepository.deleteExpiredByIds(ids, cutoff);
        RefreshToken last = batch.get(batch.size() - 1);
        return new BatchOutcome(batch.size(), deleted, last.getExpiresAt(), last.getId());
    }
}
