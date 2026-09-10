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
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

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
    private static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final char[] CODE_ALPHABET_CHARS = CODE_ALPHABET.toCharArray();
    private static final int CODE_LENGTH = 8;
    /** Forme normalisée attendue d'un code de claim (après trim + toUpperCase). */
    private static final Pattern CODE_PATTERN = Pattern.compile("^[" + CODE_ALPHABET + "]{" + CODE_LENGTH + "}$");
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

    /**
     * Revendication d'une invitation par un utilisateur authentifié
     * (R20-C). Lie le membre {@code PENDING} correspondant au compte du JWT
     * ({@code callerUserId}) et le fait passer {@code ACTIVE}, en consommant
     * l'invitation de façon atomique.
     *
     * <p>Anti-énumération : tout échec lié à l'invitation (inexistante,
     * expirée, consommée, membre non-{@code PENDING}, incohérence de tontine,
     * course perdue, mono-participation) lève une {@link IllegalStateException}
     * au message générique {@code "Invitation invalide."} — mappée en
     * HTTP 409 par {@code GlobalExceptionHandler}. Aucune information sur la
     * cause exacte n'est renvoyée.</p>
     *
     * <p>Atomicité : {@code @Transactional}. Le point de sérialisation est
     * {@link MemberInvitationRepository#consumeByIdIfActive} ; le garde-fou
     * final de mono-participation en concurrence est la contrainte
     * {@code uk_tontine_member_tontine_account}. Aucun verrou pessimiste,
     * aucun {@code @Version}. Si l'écriture du membre échoue, toute la
     * transaction est annulée : l'invitation redevient non consommée et le
     * membre reste {@code PENDING}.</p>
     *
     * @param tontineId    tontine du chemin (doit correspondre à celle du membre)
     * @param rawCode      code saisi (normalisé ici : {@code trim} + {@code toUpperCase})
     * @param callerUserId identité du JWT ({@code sub}) — jamais fournie par le corps
     * @return le membre devenu {@code ACTIVE}
     * @throws IllegalStateException (→ 409) pour toute invalidité liée au claim
     */
    @Transactional
    public TontineMember claim(Long tontineId, String rawCode, UUID callerUserId) {
        String normalized = (rawCode == null ? "" : rawCode.trim().toUpperCase(Locale.ROOT));
        if (!CODE_PATTERN.matcher(normalized).matches()) {
            throw invalidInvitation();
        }
        String codeHash = sha256Hex(normalized);

        MemberInvitation invitation = invitationRepository.findByCodeHash(codeHash)
                .orElseThrow(MemberInvitationService::invalidInvitation);

        LocalDateTime now = LocalDateTime.now();
        if (invitation.getConsumedAt() != null || !invitation.getExpiresAt().isAfter(now)) {
            throw invalidInvitation();
        }

        TontineMember member = memberRepository.findById(invitation.getTontineMemberId())
                .orElseThrow(MemberInvitationService::invalidInvitation);
        if (!member.getTontineId().equals(tontineId)) {
            throw invalidInvitation();
        }
        if (member.getStatus() != MemberStatus.PENDING) {
            throw invalidInvitation();
        }

        // Pré-check mono-participation : un compte = une seule part liée par tontine.
        if (memberRepository.findByTontineIdAndAccountId(tontineId, callerUserId).isPresent()) {
            throw invalidInvitation();
        }

        // Consommation atomique conditionnelle : 1 = gagné, 0 = déjà consommée / course perdue.
        if (invitationRepository.consumeByIdIfActive(invitation.getId(), now) != 1) {
            throw invalidInvitation();
        }

        member.setAccountId(callerUserId);
        member.setStatus(MemberStatus.ACTIVE);
        try {
            return memberRepository.saveAndFlush(member);
        } catch (DataIntegrityViolationException e) {
            // Course de mono-participation (uk_tontine_member_tontine_account) :
            // rollback complet — l'invitation redevient non consommée.
            throw invalidInvitation();
        }
    }

    private static IllegalStateException invalidInvitation() {
        return new IllegalStateException("Invitation invalide.");
    }

    private static String generateRawCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CODE_ALPHABET_CHARS[SECURE_RANDOM.nextInt(CODE_ALPHABET_CHARS.length)]);
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
