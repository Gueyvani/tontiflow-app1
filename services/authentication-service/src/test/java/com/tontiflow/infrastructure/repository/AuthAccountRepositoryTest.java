package com.tontiflow.infrastructure.repository;

import com.tontiflow.domain.enums.AccountStatus;
import com.tontiflow.domain.model.AuthAccount;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests JPA de {@link AuthAccountRepository}.
 *
 * <p>Réutilise la configuration H2 déjà définie par {@code application-test.yml}
 * ({@code @AutoConfigureTestDatabase(replace = Replace.NONE)} — pas de
 * remplacement par un datasource embarqué supplémentaire).</p>
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AuthAccountRepositoryTest {

    @Autowired
    private AuthAccountRepository authAccountRepository;

    @Test
    void findByEmail_withExistingEmail_returnsAccount() {
        AuthAccount saved = authAccountRepository.save(newAccount("existing@tontiflow.test"));

        Optional<AuthAccount> found = authAccountRepository.findByEmail("existing@tontiflow.test");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
    }

    @Test
    void findByEmail_withUnknownEmail_returnsEmpty() {
        Optional<AuthAccount> found = authAccountRepository.findByEmail("unknown@tontiflow.test");

        assertThat(found).isEmpty();
    }

    @Test
    void existsByEmail_withExistingEmail_returnsTrue() {
        authAccountRepository.save(newAccount("present@tontiflow.test"));

        assertThat(authAccountRepository.existsByEmail("present@tontiflow.test")).isTrue();
    }

    @Test
    void existsByEmail_withUnknownEmail_returnsFalse() {
        assertThat(authAccountRepository.existsByEmail("absent@tontiflow.test")).isFalse();
    }

    private static AuthAccount newAccount(String email) {
        AuthAccount account = new AuthAccount();
        account.setEmail(email);
        // Valeur de test non sensible : ce n'est pas un veritable hash BCrypt,
        // seule la colonne NOT NULL doit etre satisfaite pour ce test JPA.
        account.setPasswordHash("test-only-not-a-real-hash");
        account.setStatus(AccountStatus.ACTIVE);
        return account;
    }
}
