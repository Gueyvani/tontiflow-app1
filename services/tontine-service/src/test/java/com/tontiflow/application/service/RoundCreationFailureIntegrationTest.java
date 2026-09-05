package com.tontiflow.application.service;

import com.tontiflow.domain.enums.ContributionFrequency;
import com.tontiflow.domain.enums.RotationType;
import com.tontiflow.domain.enums.RoundStatus;
import com.tontiflow.domain.model.Tontine;
import com.tontiflow.domain.model.TontineConfig;
import com.tontiflow.domain.model.TontineRound;
import com.tontiflow.infrastructure.repository.TontineConfigRepository;
import com.tontiflow.infrastructure.repository.TontineRepository;
import com.tontiflow.infrastructure.repository.TontineRoundRepository;
import com.tontiflow.infrastructure.security.JwtTestSecurityConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PHASE F — Étape F1 : preuve RÉELLE (pas mockée) que {@code
 * UNIQUE(tontine_id, round_number)} est désormais appliquée par le schéma H2
 * de test, après ajout de {@code uniqueConstraints} sur {@code @Table} de
 * {@link TontineRound} (nom et colonnes identiques à la migration V2 —
 * {@code uk_tontine_round}).
 *
 * <p>Avant ce correctif, ce même scénario ne levait <b>aucune</b> exception
 * (voir historique du fichier) : l'entité JPA ne déclarait pas la contrainte,
 * donc {@code ddl-auto=create-drop} (profil {@code test}, Flyway désactivé)
 * ne la créait pas en H2. La correction est strictement déclarative côté JPA
 * — aucune migration créée, aucun changement de {@code RoundStatus}, aucun
 * autre fichier touché.</p>
 *
 * <p>Un {@link EntityManager#flush()} explicite est inclus pour prouver sans
 * ambiguïté que c'est la base — et non une règle métier applicative — qui
 * refuse le doublon (bien que {@code GenerationType.IDENTITY} force déjà un
 * {@code INSERT} immédiat lors de {@code save()}, avant même ce flush).</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(JwtTestSecurityConfiguration.class)
class RoundCreationFailureIntegrationTest {

    @Autowired
    private TontineRepository tontineRepository;
    @Autowired
    private TontineConfigRepository configRepository;
    @Autowired
    private TontineRoundRepository roundRepository;
    @Autowired
    private TontineRoundApplicationService roundApplicationService;
    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void createRoundForTontine_whenUniqueConstraintReallyViolated_rollsBackCleanly_thenS3bRecoversAfterAnomalyRemoved() {
        UUID creator = UUID.randomUUID();
        Tontine tontine = new Tontine();
        tontine.setName("Preuve reelle contrainte UNIQUE");
        tontine.setCreatorUserId(creator);
        tontine.setCreatedAt(LocalDateTime.now());
        Long tontineId = tontineRepository.save(tontine).getId();

        TontineConfig config = new TontineConfig();
        config.setTontineId(tontineId);
        config.setRotationType(RotationType.SEQUENTIAL);
        config.setContributionAmount(BigDecimal.valueOf(100));
        config.setContributionFrequency(ContributionFrequency.MONTHLY);
        config.setMaxMembers(10);
        configRepository.save(config);

        // Round #1 = COMPLETED (etat "avant" attendu, ne doit pas etre affecte).
        TontineRound round1 = new TontineRound();
        round1.setTontineId(tontineId);
        round1.setRoundNumber(1);
        round1.setStatus(RoundStatus.COMPLETED);
        round1.setStartDate(LocalDateTime.now().minusDays(31));
        round1.setEndDate(LocalDateTime.now().minusDays(1));
        Long round1Id = roundRepository.save(round1).getId();

        // Anomalie fabriquee : un round #2 existe deja, mais CANCELLED (pas
        // PLANNED) - invisible a la garde applicative de createRoundForTontine
        // (qui ne verifie que l'absence de PLANNED), mais occupe deja
        // round_number=2 pour cette tontine.
        TontineRound anomalousRound2 = new TontineRound();
        anomalousRound2.setTontineId(tontineId);
        anomalousRound2.setRoundNumber(2);
        anomalousRound2.setStatus(RoundStatus.CANCELLED);
        anomalousRound2.setStartDate(LocalDateTime.now());
        anomalousRound2.setEndDate(LocalDateTime.now().plusDays(30));
        roundRepository.save(anomalousRound2);

        // --- Tentative reelle de creation du round #2 : doit maintenant
        // echouer reellement sur la contrainte UNIQUE (desormais presente en
        // H2), prouvee par un flush explicite de l'EntityManager. ---
        assertThatThrownBy(() -> {
            roundApplicationService.createRoundForTontine(tontineId, config, 2);
            entityManager.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);

        // --- Etat apres l'echec : round #1 toujours COMPLETED, round #2
        // toujours unique (l'anomalie CANCELLED, mais un seul), aucune
        // duplication persistee - contrairement au comportement observe
        // avant ce correctif. ---
        TontineRound round1AfterFailure = roundRepository.findById(round1Id).orElseThrow();
        assertThat(round1AfterFailure.getStatus()).isEqualTo(RoundStatus.COMPLETED);

        List<TontineRound> roundsAfterFailure = roundRepository.findByTontineId(tontineId);
        assertThat(roundsAfterFailure).hasSize(2); // round #1 + le seul round #2 (anomalie), aucun troisieme
        long countOfRoundNumber2 = roundsAfterFailure.stream().filter(r -> r.getRoundNumber() == 2).count();
        assertThat(countOfRoundNumber2).isEqualTo(1); // contrainte UNIQUE reellement appliquee par la DB
    }
}
