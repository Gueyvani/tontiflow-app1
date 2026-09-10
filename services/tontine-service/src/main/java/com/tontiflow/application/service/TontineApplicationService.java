package com.tontiflow.application.service;

import com.tontiflow.domain.enums.ContributionFrequency;
import com.tontiflow.domain.enums.MemberStatus;
import com.tontiflow.domain.enums.NonCompliantBehavior;
import com.tontiflow.domain.enums.RotationType;
import com.tontiflow.domain.model.Tontine;
import com.tontiflow.domain.model.TontineConfig;
import com.tontiflow.domain.model.TontineMember;
import com.tontiflow.infrastructure.repository.TontineConfigRepository;
import com.tontiflow.infrastructure.repository.TontineMemberRepository;
import com.tontiflow.infrastructure.repository.TontineRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Cycle de vie de l'agrégat {@link Tontine} : création (avec sa
 * configuration, créée transactionnellement — décision métier validée),
 * ajout de membre, lecture/modification de la configuration.
 */
@Service
public class TontineApplicationService {

    private final TontineRepository tontineRepository;
    private final TontineMemberRepository memberRepository;
    private final TontineConfigRepository configRepository;
    private final TontineRoundApplicationService roundApplicationService;

    public TontineApplicationService(TontineRepository tontineRepository, TontineMemberRepository memberRepository,
                                      TontineConfigRepository configRepository,
                                      TontineRoundApplicationService roundApplicationService) {
        this.tontineRepository = tontineRepository;
        this.memberRepository = memberRepository;
        this.configRepository = configRepository;
        this.roundApplicationService = roundApplicationService;
    }

    /**
     * Crée la tontine, sa configuration et son premier round en une seule
     * opération transactionnelle (décision métier validée : une configuration
     * existe toujours dès la création, une seule par tontine — voir migration
     * V5 ; décision B1 : le premier round est créé automatiquement à la
     * création de la tontine, avec {@code roundNumber = 1} — décision D1).
     *
     * @param reorganisationAllowed valeur fournie par l'appelant, ou {@code null}
     *                              pour conserver le défaut ({@code true})
     */
    @Transactional
    public Tontine createTontine(String name, UUID creatorUserId, BigDecimal contributionAmount,
                                  ContributionFrequency contributionFrequency, int maxMembers,
                                  RotationType rotationType, NonCompliantBehavior nonCompliantBehavior,
                                  Boolean reorganisationAllowed) {
        Tontine tontine = new Tontine();
        tontine.setName(name);
        tontine.setCreatorUserId(creatorUserId);
        tontine.setCreatedAt(LocalDateTime.now());
        tontine = tontineRepository.save(tontine);

        TontineConfig config = new TontineConfig();
        config.setTontineId(tontine.getId());
        config.setContributionAmount(contributionAmount);
        config.setContributionFrequency(contributionFrequency);
        config.setMaxMembers(maxMembers);
        config.setRotationType(rotationType);
        config.setNonCompliantBehavior(nonCompliantBehavior);
        if (reorganisationAllowed != null) {
            config.setReorganisationAllowed(reorganisationAllowed);
        }
        configRepository.save(config);

        roundApplicationService.createRoundForTontine(tontine.getId(), config, 1);

        return tontine;
    }

    /**
     * Ajoute un membre à une tontine existante. Réservé au créateur de la
     * tontine (contrôle d'accès au niveau ressource).
     *
     * @throws IllegalArgumentException si la tontine n'existe pas
     * @throws AccessDeniedException    si l'appelant n'est pas le créateur de la tontine
     * @throws IllegalStateException    si l'utilisateur est déjà membre de cette tontine
     *                                  (unicité décidée explicitement, voir migration V4)
     */
    /**
     * Surcharge sans contact (compatibilité) — délègue avec
     * {@code displayName}/{@code invitedPhone} à {@code null}.
     */
    @Transactional
    public TontineMember addMember(Long tontineId, Long userId, int sequentialOrder, UUID callerUserId) {
        return addMember(tontineId, userId, sequentialOrder, null, null, callerUserId);
    }

    @Transactional
    public TontineMember addMember(Long tontineId, Long userId, int sequentialOrder,
                                   String displayName, String invitedPhone, UUID callerUserId) {
        Tontine tontine = tontineRepository.findById(tontineId)
                .orElseThrow(() -> new IllegalArgumentException("Tontine non trouvée"));
        requireCreator(tontine, callerUserId);

        memberRepository.findByTontineIdAndUserId(tontineId, userId).ifPresent(existing -> {
            throw new IllegalStateException("Cet utilisateur est déjà membre de cette tontine");
        });

        // Décision R18 D1 : un membre ajouté via l'API n'est pas encore lié à
        // un compte TontiFlow authentifiable. Il est créé PENDING, sans
        // accountId — inéligible comme bénéficiaire/destinataire de décaissement
        // (D5) jusqu'à sa liaison via le mécanisme d'invitation (R20-C).
        // displayName/invitedPhone : aides à l'invitation, optionnelles,
        // jamais un profil utilisateur (cf. UserProfile).
        TontineMember member = new TontineMember();
        member.setTontineId(tontineId);
        member.setUserId(userId);
        member.setSequentialOrder(sequentialOrder);
        member.setStatus(MemberStatus.PENDING);
        member.setDisplayName(displayName);
        member.setInvitedPhone(invitedPhone);
        return memberRepository.save(member);
    }

    /**
     * Liste les tontines dont l'appelant est le créateur (décision D1).
     * Aucun contrôle d'accès supplémentaire requis : chaque appelant ne peut
     * voir, par construction, que ses propres tontines.
     */
    public List<Tontine> listTontines(UUID callerUserId) {
        return tontineRepository.findByCreatorUserId(callerUserId);
    }

    /**
     * @throws IllegalArgumentException si la tontine n'existe pas
     * @throws AccessDeniedException    si l'appelant n'est pas le créateur de la tontine
     */
    public Tontine getTontine(Long tontineId, UUID callerUserId) {
        Tontine tontine = tontineRepository.findById(tontineId)
                .orElseThrow(() -> new IllegalArgumentException("Tontine non trouvée"));
        requireCreator(tontine, callerUserId);
        return tontine;
    }

    /**
     * @throws IllegalArgumentException si la tontine n'existe pas
     * @throws AccessDeniedException    si l'appelant n'est pas le créateur de la tontine
     */
    public List<TontineMember> listMembers(Long tontineId, UUID callerUserId) {
        getTontine(tontineId, callerUserId);
        return memberRepository.findByTontineId(tontineId);
    }

    /**
     * @throws IllegalArgumentException si la tontine ou sa configuration n'existe pas
     * @throws AccessDeniedException    si l'appelant n'est pas le créateur de la tontine
     */
    public TontineConfig getConfig(Long tontineId, UUID callerUserId) {
        getTontine(tontineId, callerUserId);
        return configRepository.findByTontineId(tontineId)
                .orElseThrow(() -> new IllegalArgumentException("Configuration non trouvée"));
    }

    /**
     * Remplace intégralement la configuration existante. Réservé au créateur
     * de la tontine.
     *
     * @throws IllegalArgumentException si la tontine ou sa configuration n'existe pas
     * @throws AccessDeniedException    si l'appelant n'est pas le créateur de la tontine
     * @throws IllegalStateException    si {@code maxMembers} est réduit sous le nombre
     *                                  réel de membres déjà présents (décision métier F1)
     */
    @Transactional
    public TontineConfig updateConfig(Long tontineId, UUID callerUserId, BigDecimal contributionAmount,
                                       ContributionFrequency contributionFrequency, int maxMembers,
                                       RotationType rotationType, NonCompliantBehavior nonCompliantBehavior,
                                       Boolean reorganisationAllowed) {
        Tontine tontine = tontineRepository.findById(tontineId)
                .orElseThrow(() -> new IllegalArgumentException("Tontine non trouvée"));
        requireCreator(tontine, callerUserId);
        TontineConfig config = configRepository.findByTontineId(tontineId)
                .orElseThrow(() -> new IllegalArgumentException("Configuration non trouvée"));

        int currentMemberCount = memberRepository.findByTontineId(tontineId).size();
        if (maxMembers < currentMemberCount) {
            throw new IllegalStateException(
                    "maxMembers ne peut pas être inférieur au nombre de membres déjà présents (" + currentMemberCount + ")");
        }

        config.setContributionAmount(contributionAmount);
        config.setContributionFrequency(contributionFrequency);
        config.setMaxMembers(maxMembers);
        config.setRotationType(rotationType);
        config.setNonCompliantBehavior(nonCompliantBehavior);
        config.setReorganisationAllowed(reorganisationAllowed != null ? reorganisationAllowed : true);

        return configRepository.save(config);
    }

    /**
     * Contrôle d'accès au niveau ressource : seul le créateur de la tontine
     * est aujourd'hui reconnaissable de façon fiable depuis l'identité JWT
     * authentifiée (UUID). {@link TontineMember#getUserId()} est un
     * identifiant {@code Long} distinct de cette identité et ne peut donc
     * pas encore servir à reconnaître un « membre » authentifié.
     */
    static void requireCreator(Tontine tontine, UUID callerUserId) {
        if (!tontine.getCreatorUserId().equals(callerUserId)) {
            throw new AccessDeniedException("Accès refusé à cette tontine");
        }
    }
}
