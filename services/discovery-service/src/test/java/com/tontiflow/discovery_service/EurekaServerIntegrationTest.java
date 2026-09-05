package com.tontiflow.discovery_service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifie le role reel de {@code discovery-service} : serveur Eureka
 * fonctionnel ({@code @EnableEurekaServer}), exposant un registre
 * d'applications, desormais protege par authentification HTTP Basic
 * service-to-service (voir {@code EurekaServerSecurityConfig}).
 *
 * <p>Ne teste aucune authentification utilisateur : les identifiants ici
 * (voir application-test.yml : {@code test}/{@code test}) sont des
 * identifiants Eureka service-to-service, pas des JWT utilisateur - ce
 * n'est pas le patron JwtVerifier/UserContext utilise par les
 * microservices metier.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class EurekaServerIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void eurekaApps_withoutCredentials_isUnauthorized() {
        ResponseEntity<String> response = restTemplate.getForEntity("/eureka/apps", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void dashboard_withoutCredentials_isUnauthorized() {
        ResponseEntity<String> response = restTemplate.getForEntity("/", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void eurekaApps_withWrongCredentials_isUnauthorized() {
        ResponseEntity<String> response = restTemplate
                .withBasicAuth("test", "wrong-password")
                .getForEntity("/eureka/apps", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void eurekaAppsRegistry_withValidCredentials_isAccessibleAndReturnsApplicationsPayload() {
        ResponseEntity<String> response = restTemplate
                .withBasicAuth("test", "test")
                .getForEntity("/eureka/apps", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("applications");
    }

    @Test
    void dashboard_withValidCredentials_isAccessible() {
        ResponseEntity<String> response = restTemplate
                .withBasicAuth("test", "test")
                .getForEntity("/", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
