package com.tontiflow.application.service;

import com.tontiflow.application.exception.DuplicatePermissionNameException;
import com.tontiflow.domain.model.Permission;
import com.tontiflow.infrastructure.repository.PermissionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires de {@link PermissionService}.
 */
@ExtendWith(MockitoExtension.class)
class PermissionServiceTest {

    @Mock
    private PermissionRepository permissionRepository;

    private PermissionService permissionService;

    @BeforeEach
    void setUp() {
        permissionService = new PermissionService(permissionRepository);
    }

    @Test
    void create_withAvailableName_createsPermission() {
        when(permissionRepository.existsByName("TONTINE_READ")).thenReturn(false);
        when(permissionRepository.save(any(Permission.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Permission created = permissionService.create("TONTINE_READ");

        assertThat(created.getName()).isEqualTo("TONTINE_READ");
    }

    @Test
    void create_withDuplicateName_throwsDuplicatePermissionNameException() {
        when(permissionRepository.existsByName("TONTINE_READ")).thenReturn(true);

        assertThatThrownBy(() -> permissionService.create("TONTINE_READ"))
                .isInstanceOf(DuplicatePermissionNameException.class);
    }

    @Test
    void findAll_returnsAllPermissions() {
        Permission permission = new Permission();
        permission.setId(UUID.randomUUID());
        permission.setName("TONTINE_WRITE");
        when(permissionRepository.findAll()).thenReturn(List.of(permission));

        List<Permission> result = permissionService.findAll();

        assertThat(result).containsExactly(permission);
    }
}
