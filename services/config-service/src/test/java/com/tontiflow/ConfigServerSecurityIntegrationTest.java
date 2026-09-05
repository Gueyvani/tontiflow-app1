package com.tontiflow;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifie le role reel de {@code config-service} : serveur Spring Cloud
 * Config fonctionnel ({@code @EnableConfigServer}, backend natif), servant
 * une configuration reelle et protege par authentification HTTP Basic
 * service-to-service (voir {@code ConfigServerSecurityConfig}).
 *
 * <p>Ne teste aucune authentification utilisateur : les identifiants ici
 * (voir application-test.yml : {@code test}/{@code test}) sont des
 * identifiants Config Server service-to-service, pas des JWT utilisateur.</p>
 */
// "native" doit rester actif en plus de "test" : @ActiveProfiles remplace la
// liste des profils actifs plutot que de s'y ajouter, et c'est le profil
// "native" qui selectionne le backend filesystem du Config Server (voir
// application.yml). Sans lui, le serveur retombe sur le backend Git par
// defaut et echoue faute d'URI de depot configuree (constat reel).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "native"})
class ConfigServerSecurityIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void configEndpoint_withoutCredentials_isUnauthorized() {
        ResponseEntity<String> response = restTemplate.getForEntity("/application/default", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void configEndpoint_withWrongCredentials_isUnauthorized() {
        ResponseEntity<String> response = restTemplate
                .withBasicAuth("test", "wrong-password")
                .getForEntity("/application/default", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void configEndpoint_withValidCredentials_returnsRealServedConfiguration() {
        ResponseEntity<String> response = restTemplate
                .withBasicAuth("test", "test")
                .getForEntity("/application/default", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("config-service.marker");
        assertThat(response.getBody()).contains("served-by-config-service");
    }

    @Test
    void actuatorHealth_withoutCredentials_isPublicAndUp() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void actuatorInfo_withoutCredentials_isUnauthorized() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/info", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void actuatorInfo_withValidCredentials_isAccessible() {
        ResponseEntity<String> response = restTemplate
                .withBasicAuth("test", "test")
                .getForEntity("/actuator/info", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
