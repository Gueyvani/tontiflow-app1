package com.tontiflow.infrastructure.bootstrap;

import com.tontiflow.application.exception.RoleAlreadyAssignedException;
import com.tontiflow.application.service.AuthAccountService;
import com.tontiflow.domain.model.AuthAccount;
import com.tontiflow.domain.model.Role;
import com.tontiflow.infrastructure.repository.AuthAccountRepository;
import com.tontiflow.infrastructure.repository.RoleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Teste directement {@link RbacAdminBootstrapRunner}, sans contexte Spring
 * (dépendances mockées, injectées via son constructeur), pour chacune des
 * branches réelles du code actuel — aucun comportement inventé.
 */
@ExtendWith(MockitoExtension.class)
class RbacAdminBootstrapRunnerTest {

    @Mock
    private AuthAccountRepository authAccountRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private AuthAccountService authAccountService;

    private RbacAdminBootstrapRunner runner(String bootstrapAdminEmail) {
        return new RbacAdminBootstrapRunner(
                bootstrapAdminEmail, authAccountRepository, roleRepository, authAccountService);
    }

    @Test
    void run_whenBootstrapEmailBlank_doesNothing() {
        runner("").run(null);

        verifyNoInteractions(authAccountRepository, roleRepository, authAccountService);
    }

    @Test
    void run_whenBootstrapEmailNull_doesNothing() {
        runner(null).run(null);

        verifyNoInteractions(authAccountRepository, roleRepository, authAccountService);
    }

    @Test
    void run_whenNoAccountForConfiguredEmail_stopsAfterLookup() {
        when(authAccountRepository.findByEmail("admin@tontiflow.test")).thenReturn(Optional.empty());

        runner("admin@tontiflow.test").run(null);

        verify(authAccountRepository).findByEmail("admin@tontiflow.test");
        verifyNoInteractions(roleRepository, authAccountService);
    }

    @Test
    void run_whenAdminRoleDoesNotExist_stopsBeforeAssignment() {
        AuthAccount account = new AuthAccount();
        account.setId(UUID.randomUUID());
        when(authAccountRepository.findByEmail("admin@tontiflow.test")).thenReturn(Optional.of(account));
        when(roleRepository.findByName("ROLE_ADMIN")).thenReturn(Optional.empty());

        runner("admin@tontiflow.test").run(null);

        verify(roleRepository).findByName("ROLE_ADMIN");
        verifyNoInteractions(authAccountService);
    }

    @Test
    void run_whenAccountAndRoleExist_assignsRoleToAccount() {
        UUID accountId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        AuthAccount account = new AuthAccount();
        account.setId(accountId);
        Role adminRole = new Role();
        adminRole.setId(roleId);

        when(authAccountRepository.findByEmail("admin@tontiflow.test")).thenReturn(Optional.of(account));
        when(roleRepository.findByName("ROLE_ADMIN")).thenReturn(Optional.of(adminRole));

        runner("admin@tontiflow.test").run(null);

        verify(authAccountService).assignRole(accountId, roleId);
    }

    @Test
    void run_whenAccountAlreadyHasAdminRole_swallowsExceptionAndCompletesNormally() {
        UUID accountId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        AuthAccount account = new AuthAccount();
        account.setId(accountId);
        Role adminRole = new Role();
        adminRole.setId(roleId);

        when(authAccountRepository.findByEmail("admin@tontiflow.test")).thenReturn(Optional.of(account));
        when(roleRepository.findByName("ROLE_ADMIN")).thenReturn(Optional.of(adminRole));
        doThrow(new RoleAlreadyAssignedException("Ce role est deja attribue a ce compte"))
                .when(authAccountService).assignRole(any(), any());

        assertThatCode(() -> runner("admin@tontiflow.test").run(null)).doesNotThrowAnyException();

        verify(authAccountService).assignRole(accountId, roleId);
    }
}
