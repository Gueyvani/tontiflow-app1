package com.tontiflow.interfaces.rest;

import com.tontiflow.UserContext;
import com.tontiflow.core.dto.ErrorResponse;
import com.tontiflow.domain.model.AuthAccount;
import com.tontiflow.domain.model.Permission;
import com.tontiflow.domain.model.Role;
import com.tontiflow.infrastructure.repository.AuthAccountRepository;
import com.tontiflow.infrastructure.repository.PermissionRepository;
import com.tontiflow.infrastructure.repository.RoleRepository;
import com.tontiflow.infrastructure.security.jwt.AccessTokenService;
import com.tontiflow.infrastructure.security.jwt.JwtTestSecurityConfiguration;
import com.tontiflow.interfaces.rest.dto.CreatePermissionRequest;
import com.tontiflow.interfaces.rest.dto.CreateRoleRequest;
import com.tontiflow.interfaces.rest.dto.LoginRequest;
import com.tontiflow.interfaces.rest.dto.PermissionResponse;
import com.tontiflow.interfaces.rest.dto.RegisterRequest;
import com.tontiflow.interfaces.rest.dto.RoleResponse;
import com.tontiflow.interfaces.rest.dto.TokenResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test d'intégration de l'administration RBAC ({@link RbacController}).
 *
 * <p>Même pattern que {@code AuthControllerIntegrationTest} : paire de clés
 * RSA éphémère ({@link JwtTestSecurityConfiguration}), aucune clé réelle.
 * Comme le profil {@code test} désactive Flyway, {@code ROLE_ADMIN} n'existe
 * pas via la migration V2 dans ces tests : il est créé directement via
 * {@link RoleRepository}, à l'identique de la pratique déjà établie pour les
 * statuts de compte dans {@code AuthControllerIntegrationTest}.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(JwtTestSecurityConfiguration.class)
class RbacControllerIntegrationTest {

    private static final String TEST_PASSWORD = "S3cur3-Test-Passw0rd!";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AuthAccountRepository authAccountRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private AccessTokenService accessTokenService;

    @Test
    void createPermission_withValidData_returns201() {
        String adminToken = provisionAdmin("perm-create-admin@tontiflow.test");

        ResponseEntity<PermissionResponse> response = restTemplate.exchange(
                "/api/v1/admin/permissions", HttpMethod.POST,
                withBearer(adminToken, new CreatePermissionRequest("TONTINE_READ")), PermissionResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().name()).isEqualTo("TONTINE_READ");
    }

    @Test
    void createPermission_withDuplicateName_returns409() {
        String adminToken = provisionAdmin("perm-dup-admin@tontiflow.test");
        restTemplate.exchange("/api/v1/admin/permissions", HttpMethod.POST,
                withBearer(adminToken, new CreatePermissionRequest("DUP_PERMISSION")), PermissionResponse.class);

        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
                "/api/v1/admin/permissions", HttpMethod.POST,
                withBearer(adminToken, new CreatePermissionRequest("DUP_PERMISSION")), ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void listPermissions_returns200() {
        String adminToken = provisionAdmin("perm-list-admin@tontiflow.test");

        ResponseEntity<PermissionResponse[]> response = restTemplate.exchange(
                "/api/v1/admin/permissions", HttpMethod.GET, withBearer(adminToken, null), PermissionResponse[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void createRole_withValidData_returns201() {
        String adminToken = provisionAdmin("role-create-admin@tontiflow.test");

        ResponseEntity<RoleResponse> response = restTemplate.exchange(
                "/api/v1/admin/roles", HttpMethod.POST,
                withBearer(adminToken, new CreateRoleRequest("ROLE_TREASURER")), RoleResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().name()).isEqualTo("ROLE_TREASURER");
    }

    @Test
    void createRole_withDuplicateName_returns409() {
        String adminToken = provisionAdmin("role-dup-admin@tontiflow.test");
        restTemplate.exchange("/api/v1/admin/roles", HttpMethod.POST,
                withBearer(adminToken, new CreateRoleRequest("ROLE_DUP")), RoleResponse.class);

        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
                "/api/v1/admin/roles", HttpMethod.POST,
                withBearer(adminToken, new CreateRoleRequest("ROLE_DUP")), ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void listRoles_returns200() {
        String adminToken = provisionAdmin("role-list-admin@tontiflow.test");

        ResponseEntity<RoleResponse[]> response = restTemplate.exchange(
                "/api/v1/admin/roles", HttpMethod.GET, withBearer(adminToken, null), RoleResponse[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void addPermissionToRole_succeeds() {
        String adminToken = provisionAdmin("assoc-admin@tontiflow.test");
        Role role = persistRole("ROLE_ASSOC_TARGET");
        Permission permission = persistPermission("ASSOC_PERMISSION");

        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/v1/admin/roles/" + role.getId() + "/permissions/" + permission.getId(),
                HttpMethod.PUT, withBearer(adminToken, null), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void addPermissionToRole_alreadyAssociated_returns409() {
        String adminToken = provisionAdmin("assoc-dup-admin@tontiflow.test");
        Permission permission = persistPermission("ASSOC_DUP_PERMISSION");
        Role role = persistRoleWithPermissions("ROLE_ASSOC_DUP", Set.of(permission));

        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
                "/api/v1/admin/roles/" + role.getId() + "/permissions/" + permission.getId(),
                HttpMethod.PUT, withBearer(adminToken, null), ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void addPermissionToRole_withUnknownPermission_returns404() {
        String adminToken = provisionAdmin("unknown-permission-admin@tontiflow.test");
        Role role = persistRole("ROLE_UNKNOWN_PERMISSION");

        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
                "/api/v1/admin/roles/" + role.getId() + "/permissions/" + UUID.randomUUID(),
                HttpMethod.PUT, withBearer(adminToken, null), ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void assignRoleToAccount_succeeds() {
        String adminToken = provisionAdmin("assign-admin@tontiflow.test");
        AuthAccount target = registerAccount("assign-target@tontiflow.test");
        Role role = persistRole("ROLE_ASSIGN_TARGET");

        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/v1/admin/accounts/" + target.getId() + "/roles/" + role.getId(),
                HttpMethod.PUT, withBearer(adminToken, null), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void assignRoleToAccount_alreadyAssigned_returns409() {
        String adminToken = provisionAdmin("assign-dup-admin@tontiflow.test");
        Role role = persistRole("ROLE_ASSIGN_DUP");
        AuthAccount target = registerAccount("assign-dup-target@tontiflow.test");
        target.setRoles(new HashSet<>(Set.of(role)));
        authAccountRepository.save(target);

        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
                "/api/v1/admin/accounts/" + target.getId() + "/roles/" + role.getId(),
                HttpMethod.PUT, withBearer(adminToken, null), ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void assignRoleToAccount_withUnknownAccount_returns404() {
        String adminToken = provisionAdmin("unknown-account-admin@tontiflow.test");
        Role role = persistRole("ROLE_UNKNOWN_ACCOUNT");

        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
                "/api/v1/admin/accounts/" + UUID.randomUUID() + "/roles/" + role.getId(),
                HttpMethod.PUT, withBearer(adminToken, null), ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void assignRoleToAccount_withUnknownRole_returns404() {
        String adminToken = provisionAdmin("unknown-role-admin@tontiflow.test");
        AuthAccount target = registerAccount("unknown-role-target@tontiflow.test");

        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
                "/api/v1/admin/accounts/" + target.getId() + "/roles/" + UUID.randomUUID(),
                HttpMethod.PUT, withBearer(adminToken, null), ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void removeRoleFromAccount_succeeds() {
        String adminToken = provisionAdmin("remove-admin@tontiflow.test");
        Role role = persistRole("ROLE_REMOVE_TARGET");
        AuthAccount target = registerAccount("remove-target@tontiflow.test");
        target.setRoles(new HashSet<>(Set.of(role)));
        authAccountRepository.save(target);

        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/v1/admin/accounts/" + target.getId() + "/roles/" + role.getId(),
                HttpMethod.DELETE, withBearer(adminToken, null), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void removeRoleFromAccount_notAssigned_returns404() {
        String adminToken = provisionAdmin("remove-404-admin@tontiflow.test");
        Role role = persistRole("ROLE_REMOVE_404");
        AuthAccount target = registerAccount("remove-404-target@tontiflow.test");

        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
                "/api/v1/admin/accounts/" + target.getId() + "/roles/" + role.getId(),
                HttpMethod.DELETE, withBearer(adminToken, null), ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void adminEndpoint_withoutJwt_returns401() {
        ResponseEntity<ErrorResponse> response = restTemplate.getForEntity(
                "/api/v1/admin/permissions", ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void adminEndpoint_withAuthenticatedNonAdmin_returns403() {
        AuthAccount plain = registerAccount("plain-user@tontiflow.test");
        String token = accessTokenService.generate(
                new UserContext(plain.getId(), plain.getEmail(), plain.getEmail(), Set.of(), Set.of()));

        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
                "/api/v1/admin/permissions", HttpMethod.GET, withBearer(token, null), ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void loginBeforeAndAfterRoleAssignment_reflectsRoleOnlyOnNewToken() {
        AuthAccount target = registerAccount("jwt-flow@tontiflow.test");
        Role role = persistRole("ROLE_JWT_FLOW");
        Permission permission = persistPermission("JWT_FLOW_PERMISSION");
        role.setPermissions(new HashSet<>(Set.of(permission)));
        roleRepository.save(role);

        // Login AVANT attribution : JWT sans le role.
        ResponseEntity<TokenResponse> beforeResponse = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest("jwt-flow@tontiflow.test", TEST_PASSWORD), TokenResponse.class);
        String oldToken = beforeResponse.getBody().accessToken();
        UserContext contextBefore = accessTokenService.validate(oldToken);
        assertThat(contextBefore.roles()).doesNotContain("ROLE_JWT_FLOW");

        // Attribution du role (equivalent direct de PUT /accounts/{id}/roles/{id}).
        target.setRoles(new HashSet<>(Set.of(role)));
        authAccountRepository.save(target);

        // Nouveau login : nouveau JWT avec le role et sa permission.
        ResponseEntity<TokenResponse> afterResponse = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest("jwt-flow@tontiflow.test", TEST_PASSWORD), TokenResponse.class);
        String newToken = afterResponse.getBody().accessToken();
        UserContext contextAfter = accessTokenService.validate(newToken);

        assertThat(contextAfter.roles()).contains("ROLE_JWT_FLOW");
        assertThat(contextAfter.permissions()).contains("JWT_FLOW_PERMISSION");

        // L'ancien token reste valide et n'est jamais modifie retroactivement.
        UserContext oldTokenStillValid = accessTokenService.validate(oldToken);
        assertThat(oldTokenStillValid.roles()).doesNotContain("ROLE_JWT_FLOW");
    }

    private String provisionAdmin(String email) {
        AuthAccount account = registerAccount(email);
        Role adminRole = roleRepository.findByName("ROLE_ADMIN").orElseGet(() -> persistRole("ROLE_ADMIN"));
        account.setRoles(new HashSet<>(Set.of(adminRole)));
        authAccountRepository.save(account);

        ResponseEntity<TokenResponse> response = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), TokenResponse.class);
        return response.getBody().accessToken();
    }

    private AuthAccount registerAccount(String email) {
        restTemplate.postForEntity("/api/v1/auth/register", new RegisterRequest(email, TEST_PASSWORD), Void.class);
        return authAccountRepository.findByEmail(email).orElseThrow();
    }

    private Role persistRole(String name) {
        Role role = new Role();
        role.setName(name);
        return roleRepository.save(role);
    }

    private Role persistRoleWithPermissions(String name, Set<Permission> permissions) {
        Role role = new Role();
        role.setName(name);
        role.setPermissions(new HashSet<>(permissions));
        return roleRepository.save(role);
    }

    private Permission persistPermission(String name) {
        Permission permission = new Permission();
        permission.setName(name);
        return permissionRepository.save(permission);
    }

    private static <T> HttpEntity<T> withBearer(String token, T body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(body, headers);
    }
}
