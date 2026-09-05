package com.tontiflow.infrastructure.repository;

import com.tontiflow.domain.model.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests JPA de {@link RoleRepository}.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RoleRepositoryTest {

    @Autowired
    private RoleRepository roleRepository;

    @Test
    void findByName_withExistingName_returnsRole() {
        Role saved = roleRepository.save(newRole("ROLE_EXISTING"));

        Optional<Role> found = roleRepository.findByName("ROLE_EXISTING");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
    }

    @Test
    void findByName_withUnknownName_returnsEmpty() {
        Optional<Role> found = roleRepository.findByName("ROLE_UNKNOWN");

        assertThat(found).isEmpty();
    }

    @Test
    void existsByName_withExistingName_returnsTrue() {
        roleRepository.save(newRole("ROLE_PRESENT"));

        assertThat(roleRepository.existsByName("ROLE_PRESENT")).isTrue();
    }

    @Test
    void existsByName_withUnknownName_returnsFalse() {
        assertThat(roleRepository.existsByName("ROLE_ABSENT")).isFalse();
    }

    private static Role newRole(String name) {
        Role role = new Role();
        role.setName(name);
        return role;
    }
}
