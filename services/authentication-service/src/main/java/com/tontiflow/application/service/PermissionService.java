package com.tontiflow.application.service;

import com.tontiflow.application.exception.DuplicatePermissionNameException;
import com.tontiflow.domain.model.Permission;
import com.tontiflow.infrastructure.repository.PermissionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Gère le cycle de vie des permissions RBAC.
 */
@Service
public class PermissionService {

    private final PermissionRepository permissionRepository;

    public PermissionService(PermissionRepository permissionRepository) {
        this.permissionRepository = permissionRepository;
    }

    /**
     * Crée une nouvelle permission.
     *
     * @param name nom unique de la permission
     * @return la permission créée et persistée
     * @throws DuplicatePermissionNameException si une permission existe déjà avec ce nom
     */
    @Transactional
    public Permission create(String name) {
        if (permissionRepository.existsByName(name)) {
            throw new DuplicatePermissionNameException("Une permission existe deja avec ce nom");
        }

        Permission permission = new Permission();
        permission.setName(name);
        return permissionRepository.save(permission);
    }

    /**
     * Liste l'ensemble des permissions existantes.
     *
     * @return toutes les permissions
     */
    @Transactional(readOnly = true)
    public List<Permission> findAll() {
        return permissionRepository.findAll();
    }
}
