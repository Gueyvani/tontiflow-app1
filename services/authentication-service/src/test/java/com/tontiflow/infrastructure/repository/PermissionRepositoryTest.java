package com.tontiflow.infrastructure.repository;

import com.tontiflow.domain.model.Permission;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests JPA de {@link PermissionRepository}.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PermissionRepositoryTest {

    @Autowired
    private PermissionRepository permissionRepository;

    @Test
    void findByName_withExistingName_returnsPermission() {
        Permission saved = permissionRepository.save(newPermission("EXISTING_PERMISSION"));

        Optional<Permission> found = permissionRepository.findByName("EXISTING_PERMISSION");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
    }

    @Test
    void findByName_withUnknownName_returnsEmpty() {
        Optional<Permission> found = permissionRepository.findByName("UNKNOWN_PERMISSION");

        assertThat(found).isEmpty();
    }

    @Test
    void existsByName_withExistingName_returnsTrue() {
        permissionRepository.save(newPermission("PRESENT_PERMISSION"));

        assertThat(permissionRepository.existsByName("PRESENT_PERMISSION")).isTrue();
    }

    @Test
    void existsByName_withUnknownName_returnsFalse() {
        assertThat(permissionRepository.existsByName("ABSENT_PERMISSION")).isFalse();
    }

    private static Permission newPermission(String name) {
        Permission permission = new Permission();
        permission.setName(name);
        return permission;
    }
}
