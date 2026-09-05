package com.tontiflow;

import com.tontiflow.infrastructure.security.JwtTestSecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

// JwtTestSecurityConfiguration fournit une paire de cles RSA generee en memoire :
// sans cet import, le contexte complet echouerait a demarrer car SecurityConfig
// (cle publique reelle) est desactivee en profil "test" (@Profile("!test")).
// Meme ajustement que celui deja applique aux ApplicationTests des 5 services
// deja securises.
@SpringBootTest
@ActiveProfiles("test")
@Import(JwtTestSecurityConfiguration.class)
class NotificationServiceApplicationTests {

    @Test
    void contextLoads() {
    }

}