package com.tontiflow.domain.model;

import com.tontiflow.domain.enums.AccountStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * Compte d'identité géré par {@code authentication-service}.
 *
 * <p>Ce compte est la source de vérité des identifiants de connexion
 * (email + hash de mot de passe) au sein du périmètre {@code authentication_db},
 * indépendamment du profil métier de l'utilisateur porté par {@code user-service}.
 * Le rapprochement entre les deux se fera ultérieurement via un identifiant
 * utilisateur commun, hors périmètre de cette étape.</p>
 *
 * <p>L'identifiant {@link #id} est un {@link UUID} généré côté application
 * (stratégie {@link GenerationType#UUID}, supportée nativement par Hibernate 6),
 * afin de rester directement compatible avec le champ {@code userId} de
 * {@code com.tontiflow.UserContext} (module {@code security-common}), qui sera
 * utilisé pour porter le claim {@code sub} du JWT dans une phase ultérieure.</p>
 */
@Entity
@Table(name = "auth_account")
public class AuthAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Adresse email, identifiant de connexion unique du compte. */
    @Column(nullable = false, unique = true)
    private String email;

    /** Hash du mot de passe (ex. : BCrypt) — le mot de passe en clair n'est jamais persisté. */
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    /** Statut courant du compte, déterminant sa capacité à s'authentifier. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AccountStatus status = AccountStatus.ACTIVE;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "account_role",
            joinColumns = @JoinColumn(name = "account_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id")
    )
    private Set<Role> roles = new HashSet<>();

    public AuthAccount() {
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public void setStatus(AccountStatus status) {
        this.status = status;
    }

    public Set<Role> getRoles() {
        return roles;
    }

    public void setRoles(Set<Role> roles) {
        this.roles = roles;
    }
}
