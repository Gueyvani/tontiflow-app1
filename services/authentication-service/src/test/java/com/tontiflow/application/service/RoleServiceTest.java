package com.tontiflow.application.service;

import com.tontiflow.application.exception.DuplicateRoleNameException;
import com.tontiflow.application.exception.PermissionAlreadyAssignedException;
import com.tontiflow.application.exception.PermissionNotFoundException;
import com.tontiflow.application.exception.RoleNotFoundException;
import com.tontiflow.domain.model.Permission;
import com.tontiflow.domain.model.Role;
import com.tontiflow.infrastructure.repository.PermissionRepository;
import com.tontiflow.infrastructure.repository.RoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires de {@link RoleService}.
 */
@ExtendWith(MockitoExtension.class)
class RoleServiceTest {

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private PermissionRepository permissionRepository;

    private RoleService roleService;

    @BeforeEach
    void setUp() {
        roleService = new RoleService(roleRepository, permissionRepository);
    }

    @Test
    void create_withAvailableName_createsRole() {
        when(roleRepository.existsByName("ROLE_MEMBER")).thenReturn(false);
        when(roleRepository.save(any(Role.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Role created = roleService.create("ROLE_MEMBER");

        assertThat(created.getName()).isEqualTo("ROLE_MEMBER");
    }

    @Test
    void create_withDuplicateName_throwsDuplicateRoleNameException() {
        when(roleRepository.existsByName("ROLE_MEMBER")).thenReturn(true);

        assertThatThrownBy(() -> roleService.create("ROLE_MEMBER"))
                .isInstanceOf(DuplicateRoleNameException.class);
    }

    @Test
    void findAll_returnsAllRoles() {
        // Decision R14-E : RoleService.findAll() delegue desormais a
        // RoleRepository.findAllWithPermissions() (LEFT JOIN FETCH, corrige
        // le N+1) plutot qu'a findAll() + initialisation lazy forcee.
        Role role = roleWithPermissions("ROLE_MEMBER", Set.of());
        when(roleRepository.findAllWithPermissions()).thenReturn(List.of(role));

        List<Role> result = roleService.findAll();

        assertThat(result).containsExactly(role);
    }

    @Test
    void addPermission_withExistingRoleAndPermission_associatesThem() {
        Role role = roleWithPermissions("ROLE_MEMBER", new HashSet<>());
        Permission permission = permissionWithId("TONTINE_READ");

        when(roleRepository.findById(role.getId())).thenReturn(Optional.of(role));
        when(permissionRepository.findById(permission.getId())).thenReturn(Optional.of(permission));

        roleService.addPermission(role.getId(), permission.getId());

        assertThat(role.getPermissions()).contains(permission);
    }

    @Test
    void addPermission_withUnknownRole_throwsRoleNotFoundException() {
        UUID roleId = UUID.randomUUID();
        when(roleRepository.findById(roleId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> roleService.addPermission(roleId, UUID.randomUUID()))
                .isInstanceOf(RoleNotFoundException.class);
    }

    @Test
    void addPermission_withUnknownPermission_throwsPermissionNotFoundException() {
        Role role = roleWithPermissions("ROLE_MEMBER", new HashSet<>());
        UUID permissionId = UUID.randomUUID();

        when(roleRepository.findById(role.getId())).thenReturn(Optional.of(role));
        when(permissionRepository.findById(permissionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> roleService.addPermission(role.getId(), permissionId))
                .isInstanceOf(PermissionNotFoundException.class);
    }

    @Test
    void addPermission_alreadyAssociated_throwsPermissionAlreadyAssignedException() {
        Permission permission = permissionWithId("TONTINE_READ");
        Role role = roleWithPermissions("ROLE_MEMBER", new HashSet<>(Set.of(permission)));

        when(roleRepository.findById(role.getId())).thenReturn(Optional.of(role));
        when(permissionRepository.findById(permission.getId())).thenReturn(Optional.of(permission));

        assertThatThrownBy(() -> roleService.addPermission(role.getId(), permission.getId()))
                .isInstanceOf(PermissionAlreadyAssignedException.class);
    }

    private static Role roleWithPermissions(String name, Set<Permission> permissions) {
        Role role = new Role();
        role.setId(UUID.randomUUID());
        role.setName(name);
        role.setPermissions(permissions);
        return role;
    }

    private static Permission permissionWithId(String name) {
        Permission permission = new Permission();
        permission.setId(UUID.randomUUID());
        permission.setName(name);
        return permission;
    }
}
