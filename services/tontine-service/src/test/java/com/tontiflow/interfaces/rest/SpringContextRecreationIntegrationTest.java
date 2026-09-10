package com.tontiflow.interfaces.rest;

import com.tontiflow.TontineServiceApplication;
import com.tontiflow.application.service.OrphanedCompletedRoundRetryScheduler;
import com.tontiflow.application.service.SuspendedRoundRetryScheduler;
import com.tontiflow.domain.enums.ContributionFrequency;
import com.tontiflow.domain.enums.MemberStatus;
import com.tontiflow.domain.enums.RotationType;
import com.tontiflow.domain.enums.RoundStatus;
import com.tontiflow.domain.model.Tontine;
import com.tontiflow.domain.model.TontineConfig;
import com.tontiflow.domain.model.TontineMember;
import com.tontiflow.domain.model.TontineRound;
import com.tontiflow.infrastructure.repository.TontineConfigRepository;
import com.tontiflow.infrastructure.repository.TontineMemberRepository;
import com.tontiflow.infrastructure.repository.TontineRepository;
import com.tontiflow.infrastructure.repository.TontineRoundRepository;
import com.tontiflow.infrastructure.security.JwtTestSecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Démontre qu'un {@link org.springframework.context.ApplicationContext} Spring
 * <b>recréé</b> — un second contexte, avec de nouvelles instances de bean et
 * de nouveaux proxies AOP, connecté à la même base H2 (persistante grâce à
 * {@code DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE}) — reprend correctement le
 * traitement à partir du seul état persistant en base, sans dépendre d'aucun
 * état mémoire du contexte d'origine.
 *
 * <p><b>Important, à ne jamais mal interpréter</b> : ceci est un test de
 * <b>recréation de contexte Spring</b> (même JVM, seconde {@code
 * ApplicationContext} construite explicitement avec {@link
 * SpringApplicationBuilder}), <u>pas</u> un test de redémarrage JVM réel ni de
 * crash/reprise du processus. Un véritable test de ce type nécessiterait un
 * arrêt/relance du processus (ou un environnement Testcontainers dédié) —
 * non réalisable dans ce harness JUnit intra-process.</p>
 *
 * <p>Le second contexte surcharge {@code spring.jpa.hibernate.ddl-auto} à
 * {@code none} (au lieu de {@code create-drop} du profil {@code test}) —
 * sans cela, son démarrage réinitialiserait le schéma et effacerait l'état
 * préparé par le contexte d'origine, ce qui invaliderait la preuve.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(JwtTestSecurityConfiguration.class)
class SpringContextRecreationIntegrationTest {

    @Autowired
    private TontineRepository tontineRepository;
    @Autowired
    private TontineConfigRepository configRepository;
    @Autowired
    private TontineMemberRepository memberRepository;
    @Autowired
    private TontineRoundRepository roundRepository;

    @Test
    void suspendedRound_recoversAfterSpringContextRecreation_notJvmRestart() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontine(creator);

        TontineConfig config = new TontineConfig();
        config.setTontineId(tontineId);
        config.setRotationType(RotationType.SEQUENTIAL);
        config.setContributionAmount(BigDecimal.valueOf(100));
        config.setContributionFrequency(ContributionFrequency.MONTHLY);
        config.setMaxMembers(10);
        configRepository.save(config);

        TontineRound round = new TontineRound();
        round.setTontineId(tontineId);
        round.setRoundNumber(1);
        round.setStatus(RoundStatus.SUSPENDED);
        round.setStartDate(LocalDateTime.now());
        round.setEndDate(LocalDateTime.now().plusDays(30));
        Long roundId = roundRepository.save(round).getId();

        TontineMember member = new TontineMember();
        member.setTontineId(tontineId);
        member.setUserId(77L);
        member.setSequentialOrder(1);
        member.setStatus(MemberStatus.ACTIVE);
        member.setAccountId(UUID.randomUUID());
        Long memberId = memberRepository.save(member).getId();

        // --- Contexte Spring RECRÉÉ (pas un redémarrage JVM) ---
        ConfigurableApplicationContext recreatedContext = bootRecreatedContext();
        try {
            SuspendedRoundRetryScheduler recreatedScheduler =
                    recreatedContext.getBean(SuspendedRoundRetryScheduler.class);
            recreatedScheduler.retrySuspendedRounds();
        } finally {
            recreatedContext.close();
        }

        // Vérification via le repository du contexte ORIGINAL — même base H2 physique.
        TontineRound afterRecreation = roundRepository.findById(roundId).orElseThrow();
        assertThat(afterRecreation.getStatus()).isEqualTo(RoundStatus.ASSIGNED);
        assertThat(afterRecreation.getBeneficiaryId()).isEqualTo(memberId);
        assertThat(roundRepository.findByTontineId(tontineId)).hasSize(1); // aucune duplication
    }

    @Test
    void orphanedCompletedRound_recoversAfterSpringContextRecreation_notJvmRestart() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontine(creator);

        TontineConfig config = new TontineConfig();
        config.setTontineId(tontineId);
        config.setRotationType(RotationType.SEQUENTIAL);
        config.setContributionAmount(BigDecimal.valueOf(250));
        config.setContributionFrequency(ContributionFrequency.WEEKLY);
        config.setMaxMembers(10);
        configRepository.save(config);

        TontineRound round = new TontineRound();
        round.setTontineId(tontineId);
        round.setRoundNumber(1);
        round.setStatus(RoundStatus.COMPLETED);
        round.setStartDate(LocalDateTime.now().minusDays(7));
        round.setEndDate(LocalDateTime.now().minusDays(1));
        roundRepository.save(round);
        // Aucun round #2 PLANNED : tontine orpheline.

        ConfigurableApplicationContext recreatedContext = bootRecreatedContext();
        try {
            OrphanedCompletedRoundRetryScheduler recreatedScheduler =
                    recreatedContext.getBean(OrphanedCompletedRoundRetryScheduler.class);
            recreatedScheduler.retryOrphanedCompletedRounds();
        } finally {
            recreatedContext.close();
        }

        List<TontineRound> rounds = roundRepository.findByTontineId(tontineId);
        assertThat(rounds).hasSize(2); // round #1 + exactement un round #2, aucune duplication
        TontineRound next = rounds.stream()
                .filter(r -> r.getRoundNumber() == 2)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Round #2 introuvable après recréation du contexte"));
        assertThat(next.getStatus()).isEqualTo(RoundStatus.PLANNED);
        assertThat(next.getBeneficiaryId()).isNull();
        assertThat(next.getAmount()).isEqualByComparingTo(BigDecimal.valueOf(250));
    }

    /**
     * Construit un second {@link ConfigurableApplicationContext}, distinct du
     * contexte du test ({@code @SpringBootTest}), pointant sur la même base
     * H2 en mémoire. {@code ddl-auto=none} est indispensable : sans cette
     * surcharge, {@code create-drop} (profil {@code test}) effacerait le
     * schéma et les données préparées par le contexte d'origine.
     */
    private static ConfigurableApplicationContext bootRecreatedContext() {
        // IMPORTANT : .properties(Map) positionne des propriétés de PLUS BASSE
        // priorité que application-test.yml (profil "test") — une tentative
        // initiale via .properties(...) a été silencieusement ignorée,
        // laissant "create-drop" actif et détruisant le schéma H2 partagé à
        // la fermeture du contexte recréé. Un argument de type ligne de
        // commande a la priorité la PLUS HAUTE dans Spring Boot : c'est le
        // seul moyen fiable de surcharger ddl-auto ici.
        return new SpringApplicationBuilder(TontineServiceApplication.class, JwtTestSecurityConfiguration.class)
                .web(WebApplicationType.NONE)
                .profiles("test")
                .run("--spring.jpa.hibernate.ddl-auto=none");
    }

    private Long createTontine(UUID creator) {
        Tontine tontine = new Tontine();
        tontine.setName("Contexte recree");
        tontine.setCreatorUserId(creator);
        tontine.setCreatedAt(LocalDateTime.now());
        return tontineRepository.save(tontine).getId();
    }
}
