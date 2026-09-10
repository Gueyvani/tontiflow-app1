package com.tontiflow.application.service;

import com.tontiflow.domain.enums.MemberStatus;
import com.tontiflow.domain.model.MemberInvitation;
import com.tontiflow.domain.model.Tontine;
import com.tontiflow.domain.model.TontineMember;
import com.tontiflow.infrastructure.repository.MemberInvitationRepository;
import com.tontiflow.infrastructure.repository.TontineMemberRepository;
import com.tontiflow.infrastructure.repository.TontineRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemberInvitationServiceTest {

    @Mock
    private TontineRepository tontineRepository;
    @Mock
    private TontineMemberRepository memberRepository;
    @Mock
    private MemberInvitationRepository invitationRepository;

    private MemberInvitationService service() {
        return new MemberInvitationService(tontineRepository, memberRepository, invitationRepository);
    }

    private static Tontine tontineOwnedBy(UUID creator) {
        Tontine t = new Tontine();
        t.setId(1L);
        t.setCreatorUserId(creator);
        return t;
    }

    private static TontineMember member(Long id, Long tontineId, MemberStatus status) {
        TontineMember m = new TontineMember();
        m.setId(id);
        m.setTontineId(tontineId);
        m.setStatus(status);
        return m;
    }

    private void stubHappyPath(UUID creator) {
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontineOwnedBy(creator)));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member(7L, 1L, MemberStatus.PENDING)));
        when(invitationRepository.saveAndFlush(any(MemberInvitation.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void generateInvitation_forPendingMember_returnsCodeWithExpectedShape() {
        UUID creator = UUID.randomUUID();
        stubHappyPath(creator);

        MemberInvitation result = service().generateInvitation(1L, 7L, creator);

        assertThat(result.getRawCode()).hasSize(8);
        assertThat(result.getRawCode()).matches("[A-HJ-NP-Z2-9]{8}"); // alphabet sans 0/O/1/I/L
        assertThat(result.getCodeHash()).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(result.getCodeHash()).isNotEqualTo(result.getRawCode());
        assertThat(result.getConsumedAt()).isNull();
        assertThat(result.getTontineMemberId()).isEqualTo(7L);
        // expiration a +7 jours (+/- tolerance large)
        LocalDateTime in7d = LocalDateTime.now().plusDays(7);
        assertThat(result.getExpiresAt()).isBetween(in7d.minusMinutes(5), in7d.plusMinutes(5));
        assertThat(result.getIssuedAt()).isBefore(result.getExpiresAt());
    }

    @Test
    void generateInvitation_storesHashOfCode_notCodeItself() throws Exception {
        UUID creator = UUID.randomUUID();
        stubHappyPath(creator);

        MemberInvitation result = service().generateInvitation(1L, 7L, creator);

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String expectedHash = HexFormat.of().formatHex(
                digest.digest(result.getRawCode().getBytes(StandardCharsets.UTF_8)));
        assertThat(result.getCodeHash()).isEqualTo(expectedHash);
    }

    @Test
    void generateInvitation_twice_yieldsDifferentCodes() {
        UUID creator = UUID.randomUUID();
        stubHappyPath(creator);

        String code1 = service().generateInvitation(1L, 7L, creator).getRawCode();
        String code2 = service().generateInvitation(1L, 7L, creator).getRawCode();

        assertThat(code1).isNotEqualTo(code2);
    }

    @Test
    void generateInvitation_invalidatesPreviousActiveInvitationsBeforeInserting() {
        UUID creator = UUID.randomUUID();
        stubHappyPath(creator);

        service().generateInvitation(1L, 7L, creator);

        // L'invalidation en masse des invitations actives précédentes doit
        // précéder l'insertion de la nouvelle (contrainte d'index partiel).
        var inOrder = org.mockito.Mockito.inOrder(invitationRepository);
        inOrder.verify(invitationRepository).consumeActiveInvitations(eq(7L), any(LocalDateTime.class));
        inOrder.verify(invitationRepository).saveAndFlush(any(MemberInvitation.class));
    }

    @Test
    void generateInvitation_forActiveMember_isRejected() {
        UUID creator = UUID.randomUUID();
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontineOwnedBy(creator)));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member(7L, 1L, MemberStatus.ACTIVE)));

        assertThatThrownBy(() -> service().generateInvitation(1L, 7L, creator))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("déjà lié");

        verify(invitationRepository, org.mockito.Mockito.never()).saveAndFlush(any());
    }

    @Test
    void generateInvitation_byNonCreator_isForbidden() {
        UUID creator = UUID.randomUUID();
        UUID attacker = UUID.randomUUID();
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontineOwnedBy(creator)));

        assertThatThrownBy(() -> service().generateInvitation(1L, 7L, attacker))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(invitationRepository);
    }

    @Test
    void generateInvitation_forUnknownMember_throwsIllegalArgument() {
        UUID creator = UUID.randomUUID();
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontineOwnedBy(creator)));
        when(memberRepository.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().generateInvitation(1L, 7L, creator))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void generateInvitation_forMemberOfAnotherTontine_isRejected() {
        UUID creator = UUID.randomUUID();
        when(tontineRepository.findById(1L)).thenReturn(Optional.of(tontineOwnedBy(creator)));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member(7L, 999L, MemberStatus.PENDING)));

        assertThatThrownBy(() -> service().generateInvitation(1L, 7L, creator))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non trouvé");
    }

    // ================= R20-C : claim =================

    private static final String VALID_CODE = "ABCDEFGH";
    private static final UUID INV_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private static String sha256Hex(String v) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static MemberInvitation activeInvitation(Long memberId) {
        MemberInvitation inv = new MemberInvitation();
        inv.setId(INV_ID);
        inv.setTontineMemberId(memberId);
        inv.setCodeHash(sha256Hex(VALID_CODE));
        inv.setIssuedAt(LocalDateTime.now().minusHours(1));
        inv.setExpiresAt(LocalDateTime.now().plusDays(6));
        return inv;
    }

    @Test
    void claim_withValidCode_linksMemberToJwtAccountAndActivates() {
        UUID caller = UUID.randomUUID();
        when(invitationRepository.findByCodeHash(sha256Hex(VALID_CODE)))
                .thenReturn(Optional.of(activeInvitation(7L)));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member(7L, 1L, MemberStatus.PENDING)));
        when(memberRepository.findByTontineIdAndAccountId(1L, caller)).thenReturn(Optional.empty());
        when(invitationRepository.consumeByIdIfActive(eq(INV_ID), any(LocalDateTime.class))).thenReturn(1);
        when(memberRepository.saveAndFlush(any(TontineMember.class))).thenAnswer(i -> i.getArgument(0));

        TontineMember result = service().claim(1L, VALID_CODE, caller);

        assertThat(result.getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(result.getAccountId()).isEqualTo(caller);
        var inOrder = org.mockito.Mockito.inOrder(invitationRepository, memberRepository);
        inOrder.verify(invitationRepository).consumeByIdIfActive(eq(INV_ID), any(LocalDateTime.class));
        inOrder.verify(memberRepository).saveAndFlush(any(TontineMember.class));
    }

    @Test
    void claim_normalizesCode_trimAndUpperCase() {
        UUID caller = UUID.randomUUID();
        when(invitationRepository.findByCodeHash(sha256Hex(VALID_CODE)))
                .thenReturn(Optional.of(activeInvitation(7L)));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member(7L, 1L, MemberStatus.PENDING)));
        when(memberRepository.findByTontineIdAndAccountId(1L, caller)).thenReturn(Optional.empty());
        when(invitationRepository.consumeByIdIfActive(eq(INV_ID), any(LocalDateTime.class))).thenReturn(1);
        when(memberRepository.saveAndFlush(any(TontineMember.class))).thenAnswer(i -> i.getArgument(0));

        TontineMember result = service().claim(1L, "  abcdefgh \n", caller);

        assertThat(result.getStatus()).isEqualTo(MemberStatus.ACTIVE);
    }

    @Test
    void claim_withMalformedCode_isRejectedGenerically_withoutTouchingRepositories() {
        assertThatThrownBy(() -> service().claim(1L, "abc", UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Invitation invalide.");
        assertThatThrownBy(() -> service().claim(1L, "AB0DEFGH", UUID.randomUUID())) // '0' hors alphabet
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Invitation invalide.");
        verifyNoInteractions(invitationRepository, memberRepository);
    }

    @Test
    void claim_withUnknownCode_isRejectedGenerically() {
        when(invitationRepository.findByCodeHash(sha256Hex(VALID_CODE))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().claim(1L, VALID_CODE, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Invitation invalide.");
    }

    @Test
    void claim_withExpiredInvitation_isRejectedGenerically() {
        MemberInvitation inv = activeInvitation(7L);
        inv.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(invitationRepository.findByCodeHash(sha256Hex(VALID_CODE))).thenReturn(Optional.of(inv));

        assertThatThrownBy(() -> service().claim(1L, VALID_CODE, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Invitation invalide.");
        verify(invitationRepository, org.mockito.Mockito.never()).consumeByIdIfActive(any(), any());
    }

    @Test
    void claim_withConsumedInvitation_isRejectedGenerically() {
        MemberInvitation inv = activeInvitation(7L);
        inv.setConsumedAt(LocalDateTime.now().minusMinutes(1));
        when(invitationRepository.findByCodeHash(sha256Hex(VALID_CODE))).thenReturn(Optional.of(inv));

        assertThatThrownBy(() -> service().claim(1L, VALID_CODE, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Invitation invalide.");
    }

    @Test
    void claim_whenMemberMissing_isRejectedGenerically() {
        when(invitationRepository.findByCodeHash(sha256Hex(VALID_CODE)))
                .thenReturn(Optional.of(activeInvitation(7L)));
        when(memberRepository.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().claim(1L, VALID_CODE, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Invitation invalide.");
    }

    @Test
    void claim_whenTontineIdMismatch_isRejectedGenerically_withoutConsuming() {
        when(invitationRepository.findByCodeHash(sha256Hex(VALID_CODE)))
                .thenReturn(Optional.of(activeInvitation(7L)));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member(7L, 999L, MemberStatus.PENDING)));

        assertThatThrownBy(() -> service().claim(1L, VALID_CODE, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Invitation invalide.");
        verify(invitationRepository, org.mockito.Mockito.never()).consumeByIdIfActive(any(), any());
    }

    @Test
    void claim_whenMemberNotPending_isRejectedGenerically() {
        when(invitationRepository.findByCodeHash(sha256Hex(VALID_CODE)))
                .thenReturn(Optional.of(activeInvitation(7L)));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member(7L, 1L, MemberStatus.ACTIVE)));

        assertThatThrownBy(() -> service().claim(1L, VALID_CODE, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Invitation invalide.");
    }

    @Test
    void claim_whenAccountAlreadyMemberOfTontine_isRejectedGenerically_beforeConsuming() {
        UUID caller = UUID.randomUUID();
        when(invitationRepository.findByCodeHash(sha256Hex(VALID_CODE)))
                .thenReturn(Optional.of(activeInvitation(7L)));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member(7L, 1L, MemberStatus.PENDING)));
        when(memberRepository.findByTontineIdAndAccountId(1L, caller))
                .thenReturn(Optional.of(member(3L, 1L, MemberStatus.ACTIVE)));

        assertThatThrownBy(() -> service().claim(1L, VALID_CODE, caller))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Invitation invalide.");
        verify(invitationRepository, org.mockito.Mockito.never()).consumeByIdIfActive(any(), any());
    }

    @Test
    void claim_whenConditionalConsumeReturnsZero_isRejectedGenerically_lostRace() {
        UUID caller = UUID.randomUUID();
        when(invitationRepository.findByCodeHash(sha256Hex(VALID_CODE)))
                .thenReturn(Optional.of(activeInvitation(7L)));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member(7L, 1L, MemberStatus.PENDING)));
        when(memberRepository.findByTontineIdAndAccountId(1L, caller)).thenReturn(Optional.empty());
        when(invitationRepository.consumeByIdIfActive(eq(INV_ID), any(LocalDateTime.class))).thenReturn(0);

        assertThatThrownBy(() -> service().claim(1L, VALID_CODE, caller))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Invitation invalide.");
        verify(memberRepository, org.mockito.Mockito.never()).saveAndFlush(any());
    }

    @Test
    void claim_whenMemberSaveHitsUniqueConstraint_isMappedTo409NotBubbledAsDataIntegrity() {
        UUID caller = UUID.randomUUID();
        when(invitationRepository.findByCodeHash(sha256Hex(VALID_CODE)))
                .thenReturn(Optional.of(activeInvitation(7L)));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member(7L, 1L, MemberStatus.PENDING)));
        when(memberRepository.findByTontineIdAndAccountId(1L, caller)).thenReturn(Optional.empty());
        when(invitationRepository.consumeByIdIfActive(eq(INV_ID), any(LocalDateTime.class))).thenReturn(1);
        when(memberRepository.saveAndFlush(any(TontineMember.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uk_tontine_member_tontine_account"));

        assertThatThrownBy(() -> service().claim(1L, VALID_CODE, caller))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Invitation invalide.");
    }
}
