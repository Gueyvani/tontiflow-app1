package com.tontiflow.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Permission unitaire pouvant être accordée à un {@link Role}.
 *
 * <p>Une permission représente une autorisation fine (ex. : {@code TONTINE_READ},
 * {@code TONTINE_WRITE}) indépendante de tout rôle particulier. Elle est
 * réutilisable par plusieurs rôles via la relation {@link Role#getPermissions()}.</p>
 */
@Entity
@Table(name = "permission")
public class Permission {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Nom unique de la permission (ex. : {@code TONTINE_READ}). */
    @Column(nullable = false, unique = true, length = 64)
    private String name;

    public Permission() {
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
}
