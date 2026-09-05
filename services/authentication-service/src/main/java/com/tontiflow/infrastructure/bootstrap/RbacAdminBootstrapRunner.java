package com.tontiflow.infrastructure.bootstrap;

import com.tontiflow.application.exception.RoleAlreadyAssignedException;
import com.tontiflow.application.service.AuthAccountService;
import com.tontiflow.domain.model.AuthAccount;
import com.tontiflow.domain.model.Role;
import com.tontiflow.infrastructure.repository.AuthAccountRepository;
import com.tontiflow.infrastructure.repository.RoleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Attribue {@code ROLE_ADMIN} à un compte existant au démarrage de
 * l'application, uniquement si la propriété {@code rbac.bootstrap.admin-email}
 * est explicitement renseignée.
 *
 * <p>Ne crée jamais de compte : si l'email configuré ne correspond à aucun
 * {@link AuthAccount} existant (déjà créé au préalable via {@code /register}),
 * le démarrage se poursuit normalement avec un simple avertissement dans les
 * logs — comportement explicite et documenté, jamais de création silencieuse.</p>
 */
@Component
public class RbacAdminBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RbacAdminBootstrapRunner.class);
    private static final String ADMIN_ROLE_NAME = "ROLE_ADMIN";

    private final String bootstrapAdminEmail;
    private final AuthAccountRepository authAccountRepository;
    private final RoleRepository roleRepository;
    private final AuthAccountService authAccountService;

    public RbacAdminBootstrapRunner(
            @Value("${rbac.bootstrap.admin-email:}") String bootstrapAdminEmail,
            AuthAccountRepository authAccountRepository,
            RoleRepository roleRepository,
            AuthAccountService authAccountService) {
        this.bootstrapAdminEmail = bootstrapAdminEmail;
        this.authAccountRepository = authAccountRepository;
        this.roleRepository = roleRepository;
        this.authAccountService = authAccountService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (bootstrapAdminEmail == null || bootstrapAdminEmail.isBlank()) {
            return;
        }

        Optional<AuthAccount> account = authAccountRepository.findByEmail(bootstrapAdminEmail);
        if (account.isEmpty()) {
            log.warn("Bootstrap ROLE_ADMIN ignore : aucun compte trouve pour l'email configure '{}'. "
                    + "Le compte doit d'abord etre cree via /api/v1/auth/register.", bootstrapAdminEmail);
            return;
        }

        Optional<Role> adminRole = roleRepository.findByName(ADMIN_ROLE_NAME);
        if (adminRole.isEmpty()) {
            log.warn("Bootstrap ROLE_ADMIN ignore : le role '{}' n'existe pas "
                    + "(migration V2 non appliquee ?).", ADMIN_ROLE_NAME);
            return;
        }

        try {
            authAccountService.assignRole(account.get().getId(), adminRole.get().getId());
            log.info("ROLE_ADMIN attribue au compte de bootstrap configure.");
        } catch (RoleAlreadyAssignedException e) {
            // Deja administrateur lors d'un demarrage precedent : redemarrage normal, idempotent.
            log.info("Bootstrap ROLE_ADMIN : le compte configure possede deja ce role.");
        }
    }
}
