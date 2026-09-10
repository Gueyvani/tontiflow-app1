package com.tontiflow.application.service;

import com.tontiflow.domain.enums.MemberStatus;
import com.tontiflow.domain.model.MemberInvitation;
import com.tontiflow.domain.model.Tontine;
import com.tontiflow.domain.model.TontineMember;
import com.tontiflow.infrastructure.repository.MemberInvitationRepository;
import com.tontiflow.infrastructure.repository.TontineMemberRepository;
import com.tontiflow.infrastructure.repository.TontineRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Génération d'invitations de liaison pour un membre {@code PENDING}
 * (décisions R20-A / R20-B). Ne consomme aucune invitation et ne fait jamais
 * passer un membre à {@code ACTIVE} — cela relève exclusivement de R20-C.
 *
 * <p>Sécurité du code : généré par {@link SecureRandom} sur un alphabet non
 * ambigu (8 caractères, ~40 bits), seul son SHA-256 hex est persisté. Le code
 * brut n'est retourné qu'une fois, au créateur autorisé, via
 * {@link MemberInvitation#getRawCode()} — jamais loggé, jamais réexposé.</p>
 *
 * <p>Règle « une seule invitation active par membre » : garantie au niveau
 * base par l'index partiel {@code uk_member_invitation_active}
 * ({@code tontine_member_id} WHERE {@code consumed_at IS NULL}). À chaque
 * génération, les invitations actives antérieures du membre sont marquées
 * {@code consumedAt = now} (invalidation sans perte d'historique). Deux
 * générations concurrentes pour le même membre : l'index partiel en laisse
 * passer exactement une ; l'autre échoue en {@link DataIntegrityViolationException},
 * traduite ici en {@link IllegalStateException} (409) — pas de verrou
 * pessimiste, pas d'état incohérent.</p>
 */
@Service
public class MemberInvitationService {

    /** Alphabet Crockford-like sans caractères ambigus (0/O/1/I/L). 31 symboles (~40 bits sur 8 caractères). */
    private static final char[] CODE_ALPHABET =
            "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int CODE_LENGTH = 8;
    private static final Duration INVITATION_TTL = Duration.ofDays(7);
    private static final String HASH_ALGORITHM = "SHA-256";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final TontineRepository tontineRepository;
    private final TontineMemberRepository memberRepository;
    private final MemberInvitationRepository invitationRepository;

    public MemberInvitationService(TontineRepository tontineRepository,
                                   TontineMemberRepository memberRepository,
                                   MemberInvitationRepository invitationRepository) {
        this.tontineRepository = tontineRepository;
        this.memberRepository = memberRepository;
        this.invitationRepository = invitationRepository;
    }

    /**
     * Génère une nouvelle invitation active pour {@code memberId}, au nom du
     * créateur authentifié de {@code tontineId}. Invalide l'invitation active
     * précédente s'il y en avait une.
     *
     * @return l'invitation persistée, avec son code brut ({@code rawCode})
     *         disponible une seule fois
     * @throws IllegalArgumentException si la tontine ou le membre n'existe pas,
     *                                  ou si le membre n'appartient pas à cette tontine
     * @throws org.springframework.security.access.AccessDeniedException
     *                                  si l'appelant n'est pas le créateur de la tontine
     * @throws IllegalStateException    si le membre est déjà {@code ACTIVE}
     *                                  (aucune invitation nécessaire), ou en cas de
     *                                  génération concurrente
     */
    @Transactional
    public MemberInvitation generateInvitation(Long tontineId, Long memberId, UUID callerUserId) {
        Tontine tontine = tontineRepository.findById(tontineId)
                .orElseThrow(() -> new IllegalArgumentException("Tontine non trouvée"));
        TontineApplicationService.requireCreator(tontine, callerUserId);

        TontineMember member = memberRepository.findById(memberId)
                .orElseThrow(() -> new IllegalArgumentException("Membre non trouvé"));
        if (!member.getTontineId().equals(tontineId)) {
            // Même traitement que "membre non trouvé" : du point de vue de
            // cette tontine, ce membre n'existe pas.
            throw new IllegalArgumentException("Membre non trouvé");
        }
        if (member.getStatus() == MemberStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Ce membre est déjà lié à un compte : aucune invitation n'est nécessaire");
        }

        LocalDateTime now = LocalDateTime.now();

        // Invalide l'invitation active précédente (s'il y en a une) via un
        // UPDATE en masse exécuté immédiatement — nécessaire pour que la
        // nouvelle insertion ne heurte pas l'index partiel
        // uk_member_invitation_active (Hibernate ordonne sinon l'INSERT
        // avant l'UPDATE au flush).
        invitationRepository.consumeActiveInvitations(memberId, now);

        String rawCode = generateRawCode();
        MemberInvitation invitation = new MemberInvitation();
        invitation.setTontineMemberId(memberId);
        invitation.setCodeHash(sha256Hex(rawCode));
        invitation.setIssuedAt(now);
        invitation.setExpiresAt(now.plus(INVITATION_TTL));

        MemberInvitation saved;
        try {
            saved = invitationRepository.saveAndFlush(invitation);
        } catch (DataIntegrityViolationException e) {
            // Index partiel uk_member_invitation_active : une génération
            // concurrente a déjà créé l'unique invitation active de ce membre.
            throw new IllegalStateException(
                    "Une génération d'invitation concurrente est en cours pour ce membre — réessayez", e);
        }
        saved.setRawCode(rawCode);
        return saved;
    }

    private static String generateRawCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CODE_ALPHABET[SECURE_RANDOM.nextInt(CODE_ALPHABET.length)]);
        }
        return sb.toString();
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(HASH_ALGORITHM + " non disponible sur cette JVM", e);
        }
    }
}
