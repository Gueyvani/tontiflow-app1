package com.tontiflow.interfaces.rest;

import com.tontiflow.application.service.AuthAccountService;
import com.tontiflow.application.service.PermissionService;
import com.tontiflow.application.service.RoleService;
import com.tontiflow.domain.model.Permission;
import com.tontiflow.domain.model.Role;
import com.tontiflow.interfaces.rest.dto.CreatePermissionRequest;
import com.tontiflow.interfaces.rest.dto.CreateRoleRequest;
import com.tontiflow.interfaces.rest.dto.PermissionResponse;
import com.tontiflow.interfaces.rest.dto.RoleResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Administration RBAC minimale : permissions, rôles, et attribution des
 * rôles aux comptes.
 *
 * <p>Aucune logique métier ici : chaque méthode délègue intégralement à
 * {@link PermissionService}, {@link RoleService} ou {@link AuthAccountService}
 * (contrats déjà validés). Réservé aux appelants disposant de
 * {@code ROLE_ADMIN} (voir {@code SecurityConfig}).</p>
 */
@RestController
@RequestMapping("/api/v1/admin")
public class RbacController {

    private final RoleService roleService;
    private final PermissionService permissionService;
    private final AuthAccountService authAccountService;

    public RbacController(RoleService roleService, PermissionService permissionService,
                           AuthAccountService authAccountService) {
        this.roleService = roleService;
        this.permissionService = permissionService;
        this.authAccountService = authAccountService;
    }

    @PostMapping("/permissions")
    public ResponseEntity<PermissionResponse> createPermission(@Valid @RequestBody CreatePermissionRequest request) {
        Permission permission = permissionService.create(request.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(permission));
    }

    @GetMapping("/permissions")
    public ResponseEntity<List<PermissionResponse>> listPermissions() {
        List<PermissionResponse> permissions = permissionService.findAll().stream()
                .map(this::toResponse)
                .toList();
        return ResponseEntity.ok(permissions);
    }

    @PostMapping("/roles")
    public ResponseEntity<RoleResponse> createRole(@Valid @RequestBody CreateRoleRequest request) {
        Role role = roleService.create(request.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(role));
    }

    @GetMapping("/roles")
    public ResponseEntity<List<RoleResponse>> listRoles() {
        List<RoleResponse> roles = roleService.findAll().stream()
                .map(this::toResponse)
                .toList();
        return ResponseEntity.ok(roles);
    }

    @PutMapping("/roles/{roleId}/permissions/{permissionId}")
    public ResponseEntity<Void> addPermissionToRole(@PathVariable UUID roleId, @PathVariable UUID permissionId) {
        roleService.addPermission(roleId, permissionId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/accounts/{accountId}/roles/{roleId}")
    public ResponseEntity<Void> assignRoleToAccount(@PathVariable UUID accountId, @PathVariable UUID roleId) {
        authAccountService.assignRole(accountId, roleId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/accounts/{accountId}/roles/{roleId}")
    public ResponseEntity<Void> removeRoleFromAccount(@PathVariable UUID accountId, @PathVariable UUID roleId) {
        authAccountService.removeRole(accountId, roleId);
        return ResponseEntity.noContent().build();
    }

    private PermissionResponse toResponse(Permission permission) {
        return new PermissionResponse(permission.getId(), permission.getName());
    }

    private RoleResponse toResponse(Role role) {
        Set<String> permissionNames = role.getPermissions().stream()
                .map(Permission::getName)
                .collect(Collectors.toSet());
        return new RoleResponse(role.getId(), role.getName(), permissionNames);
    }
}
