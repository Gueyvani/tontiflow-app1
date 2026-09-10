package com.tontiflow.infrastructure.repository;

import com.tontiflow.domain.model.MemberInvitation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MemberInvitationRepository extends JpaRepository<MemberInvitation, UUID> {

    /**
     * Recherche par hash du code — utilisée par R20-C (le service applicatif
     * calcule le SHA-256 du code brut <b>avant</b> d'appeler cette méthode ;
     * le code brut ne circule jamais jusqu'à la requête SQL).
     */
    Optional<MemberInvitation> findByCodeHash(String codeHash);

    /** Invitations encore actives (non consommées) d'un membre. */
    List<MemberInvitation> findByTontineMemberIdAndConsumedAtIsNull(Long tontineMemberId);

    /** Historique complet des invitations d'un membre (tests / diagnostic). */
    List<MemberInvitation> findByTontineMemberId(Long tontineMemberId);

    /**
     * Invalide en une seule instruction SQL toutes les invitations actives
     * d'un membre (règle « une seule invitation active par membre »).
     *
     * <p>UPDATE en masse volontaire : exécuté immédiatement, il ne subit pas
     * l'ordonnancement Hibernate « INSERT avant UPDATE » du flush — sans quoi
     * l'insertion de la nouvelle invitation frapperait l'index partiel
     * {@code uk_member_invitation_active} avant que {@code consumed_at} de
     * l'ancienne n'ait été écrit.</p>
     *
     * @return le nombre de lignes invalidées
     */
    @Modifying
    @Query("UPDATE MemberInvitation i SET i.consumedAt = :now "
            + "WHERE i.tontineMemberId = :memberId AND i.consumedAt IS NULL")
    int consumeActiveInvitations(@Param("memberId") Long memberId, @Param("now") LocalDateTime now);
}
