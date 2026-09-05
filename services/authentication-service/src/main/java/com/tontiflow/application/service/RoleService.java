package com.tontiflow.application.service;

import com.tontiflow.application.exception.DuplicateRoleNameException;
import com.tontiflow.application.exception.PermissionAlreadyAssignedException;
import com.tontiflow.application.exception.PermissionNotFoundException;
import com.tontiflow.application.exception.RoleNotFoundException;
import com.tontiflow.domain.model.Permission;
import com.tontiflow.domain.model.Role;
import com.tontiflow.infrastructure.repository.PermissionRepository;
import com.tontiflow.infrastructure.repository.RoleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Gère le cycle de vie des rôles RBAC et leur association aux permissions.
 *
 * <p>{@code Role.permissions} est la relation dont {@code Role} est
 * propriétaire (le {@code @JoinTable role_permission} est déclaré sur
 * l'entité {@code Role}) : ce service en porte donc la responsabilité, de
 * la même manière qu'{@code AuthAccountService} porte celle d'{@code AuthAccount.roles}.</p>
 */
@Service
public class RoleService {

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;

    public RoleService(RoleRepository roleRepository, PermissionRepository permissionRepository) {
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
    }

    /**
     * Crée un nouveau rôle, sans permission associée.
     *
     * @param name nom unique du rôle
     * @return le rôle créé et persisté
     * @throws DuplicateRoleNameException si un rôle existe déjà avec ce nom
     */
    @Transactional
    public Role create(String name) {
        if (roleRepository.existsByName(name)) {
            throw new DuplicateRoleNameException("Un role existe deja avec ce nom");
        }

        Role role = new Role();
        role.setName(name);
        return roleRepository.save(role);
    }

    /**
     * Liste l'ensemble des rôles existants, avec leurs permissions déjà chargées.
     *
     * @return tous les rôles
     */
    @Transactional(readOnly = true)
    public List<Role> findAll() {
        List<Role> roles = roleRepository.findAll();
        // Role.permissions est LAZY : on force son initialisation ici, pendant que la
        // session Hibernate est encore active, pour que l'appelant (RbacController)
        // puisse mapper vers RoleResponse sans LazyInitializationException.
        roles.forEach(role -> role.getPermissions().size());
        return roles;
    }

    /**
     * Associe une permission existante à un rôle existant.
     *
     * @param roleId       identifiant du rôle
     * @param permissionId identifiant de la permission
     * @throws RoleNotFoundException             si le rôle n'existe pas
     * @throws PermissionNotFoundException       si la permission n'existe pas
     * @throws PermissionAlreadyAssignedException si la permission est déjà associée à ce rôle
     */
    @Transactional
    public void addPermission(UUID roleId, UUID permissionId) {
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new RoleNotFoundException("Role introuvable"));
        Permission permission = permissionRepository.findById(permissionId)
                .orElseThrow(() -> new PermissionNotFoundException("Permission introuvable"));

        // Comparaison par identifiant : Permission ne redefinit pas equals()/hashCode(),
        // on ne peut donc pas se fier a Set.contains(permission) de maniere fiable.
        boolean alreadyAssigned = role.getPermissions().stream()
                .anyMatch(p -> p.getId().equals(permission.getId()));
        if (alreadyAssigned) {
            throw new PermissionAlreadyAssignedException("Cette permission est deja associee a ce role");
        }

        role.getPermissions().add(permission);
    }
}
