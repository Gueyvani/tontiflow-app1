package com.tontiflow.application.service;

import com.tontiflow.domain.enums.MemberStatus;
import com.tontiflow.domain.enums.RoundStatus;
import com.tontiflow.domain.model.Tontine;
import com.tontiflow.domain.model.TontineMember;
import com.tontiflow.domain.model.TontineRound;
import com.tontiflow.infrastructure.repository.TontineMemberRepository;
import com.tontiflow.infrastructure.repository.TontineRepository;
import com.tontiflow.infrastructure.repository.TontineRoundRepository;
import com.tontiflow.infrastructure.security.JwtTestSecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PHASE F3 — {@link TontineRoundApplicationService#replaceBeneficiary} avec
 * H2 réel, vrai bean Spring, aucun mock.
 *
 * <p>Historique : ce fichier démontrait à l'origine (Phase E) trois
 * vulnérabilités réelles — bypass de l'éligibilité, réouverture d'un round
 * {@code COMPLETED}, acceptation d'un bénéficiaire d'une autre tontine.
 * Phase F3 a corrigé les Règles A (round {@code COMPLETED} rejeté) et B
 * (bénéficiaire doit appartenir à la tontine du round) — ce fichier
 * démontre maintenant la protection réelle, pour les 5 combinaisons
 * statut/appartenance explicitement demandées.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(JwtTestSecurityConfiguration.class)
class ReplaceBeneficiaryAuditTest {

    @Autowired
    private TontineRepository tontineRepository;
    @Autowired
    private TontineMemberRepository memberRepository;
    @Autowired
    private TontineRoundRepository roundRepository;
    @Autowired
    private TontineRoundApplicationService roundApplicationService;

    @Test
    void replaceBeneficiary_onPlannedRound_withBeneficiaryFromSameTontine_succeeds() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontine(creator);
        Long memberId = createMember(tontineId, 1L);
        TontineRound round = saveRound(tontineId, RoundStatus.PLANNED, null);

        TontineRound result = roundApplicationService.replaceBeneficiary(
                round.getId(), memberId, "test audit", "auditor", creator);

        assertThat(result.getStatus()).isEqualTo(RoundStatus.ASSIGNED);
        assertThat(result.getBeneficiaryId()).isEqualTo(memberId);
    }

    @Test
    void replaceBeneficiary_onCompletedRound_withBeneficiaryFromSameTontine_isRejected_andRoundUnchanged() {
        // Regle A (Phase F3) : un round COMPLETED est terminal.
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontine(creator);
        Long memberId = createMember(tontineId, 1L);
        TontineRound round = saveRound(tontineId, RoundStatus.COMPLETED, 1L);
        Long roundId = round.getId();

        assertThatThrownBy(() -> roundApplicationService.replaceBeneficiary(
                roundId, memberId, "test audit", "auditor", creator))
                .isInstanceOf(IllegalStateException.class);

        TontineRound unchanged = roundRepository.findById(roundId).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(RoundStatus.COMPLETED);
        assertThat(unchanged.getBeneficiaryId()).isEqualTo(1L); // inchange
    }

    @Test
    void replaceBeneficiary_onPlannedRound_withBeneficiaryFromAnotherTontine_isRejected_andRoundUnchanged() {
        // Regle B (Phase F3) : round.getTontineId() reste l'unique source de verite.
        UUID creatorA = UUID.randomUUID();
        Long tontineA = createTontine(creatorA);
        TontineRound roundOfA = saveRound(tontineA, RoundStatus.PLANNED, null);
        Long roundOfAId = roundOfA.getId();

        Long tontineB = createTontine(UUID.randomUUID());
        Long memberOfBId = createMember(tontineB, 42L);

        assertThatThrownBy(() -> roundApplicationService.replaceBeneficiary(
                roundOfAId, memberOfBId, "test audit", "auditor", creatorA))
                .isInstanceOf(IllegalArgumentException.class);

        TontineRound unchanged = roundRepository.findById(roundOfAId).orElseThrow();
        assertThat(unchanged.getBeneficiaryId()).isNull(); // toujours inchange
        assertThat(unchanged.getStatus()).isEqualTo(RoundStatus.PLANNED);
    }

    @Test
    void replaceBeneficiary_onSuspendedRound_withBeneficiaryFromSameTontine_succeeds() {
        // Comportement actuel volontairement conserve (aucune decision metier
        // ne restreint SUSPENDED — voir audit Phase F2, §13 Regle A).
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontine(creator);
        Long memberId = createMember(tontineId, 1L);
        TontineRound round = saveRound(tontineId, RoundStatus.SUSPENDED, null);

        TontineRound result = roundApplicationService.replaceBeneficiary(
                round.getId(), memberId, "test audit", "auditor", creator);

        assertThat(result.getStatus()).isEqualTo(RoundStatus.ASSIGNED);
        assertThat(result.getBeneficiaryId()).isEqualTo(memberId);
    }

    @Test
    void replaceBeneficiary_onAssignedRound_withBeneficiaryFromSameTontine_succeeds() {
        UUID creator = UUID.randomUUID();
        Long tontineId = createTontine(creator);
        Long memberId = createMember(tontineId, 1L);
        TontineRound round = saveRound(tontineId, RoundStatus.ASSIGNED, 999L);

        TontineRound result = roundApplicationService.replaceBeneficiary(
                round.getId(), memberId, "test audit", "auditor", creator);

        assertThat(result.getStatus()).isEqualTo(RoundStatus.ASSIGNED);
        assertThat(result.getBeneficiaryId()).isEqualTo(memberId);
    }

    private Long createTontine(UUID creator) {
        Tontine tontine = new Tontine();
        tontine.setName("Audit replaceBeneficiary");
        tontine.setCreatorUserId(creator);
        tontine.setCreatedAt(LocalDateTime.now());
        return tontineRepository.save(tontine).getId();
    }

    private Long createMember(Long tontineId, Long userId) {
        TontineMember member = new TontineMember();
        member.setTontineId(tontineId);
        member.setUserId(userId);
        member.setSequentialOrder(1);
        member.setStatus(MemberStatus.ACTIVE);
        member.setAccountId(UUID.randomUUID());
        return memberRepository.save(member).getId();
    }

    private TontineRound saveRound(Long tontineId, RoundStatus status, Long beneficiaryId) {
        TontineRound round = new TontineRound();
        round.setTontineId(tontineId);
        round.setRoundNumber(1);
        round.setBeneficiaryId(beneficiaryId);
        round.setStatus(status);
        round.setStartDate(LocalDateTime.now());
        round.setEndDate(LocalDateTime.now().plusDays(30));
        return roundRepository.save(round);
    }
}
