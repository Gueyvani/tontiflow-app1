package com.tontiflow.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Rôle applicatif regroupant un ensemble de {@link Permission}.
 *
 * <p>Un rôle est attribué à un ou plusieurs {@link AuthAccount} via la relation
 * {@link AuthAccount#getRoles()} et porte lui-même un ensemble de permissions.
 * Cette double relation many-to-many constitue le modèle RBAC
 * (Role-Based Access Control) du module {@code authentication-service}.</p>
 */
@Entity
@Table(name = "role")
public class Role {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Nom unique du rôle (ex. : {@code ROLE_USER}, {@code ROLE_ADMIN}). */
    @Column(nullable = false, unique = true, length = 64)
    private String name;

    // Relation unidirectionnelle Role -> Permission : aucun besoin identifié
    // à ce stade de naviguer depuis une Permission vers ses rôles porteurs.
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "role_permission",
            joinColumns = @JoinColumn(name = "role_id"),
            inverseJoinColumns = @JoinColumn(name = "permission_id")
    )
    private Set<Permission> permissions = new HashSet<>();

    public Role() {
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Set<Permission> getPermissions() {
        return permissions;
    }

    public void setPermissions(Set<Permission> permissions) {
        this.permissions = permissions;
    }
}
