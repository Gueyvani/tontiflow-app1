package com.tontiflow.infrastructure.repository;

import com.tontiflow.domain.model.AuthAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Accès en persistance aux comptes d'authentification ({@link AuthAccount}).
 */
@Repository
public interface AuthAccountRepository extends JpaRepository<AuthAccount, UUID> {

    /**
     * Recherche un compte par son email (identifiant de connexion unique).
     *
     * @param email email recherché
     * @return le compte correspondant, ou {@link Optional#empty()} si aucun
     */
    Optional<AuthAccount> findByEmail(String email);

    /**
     * Indique si un compte existe déjà pour l'email donné.
     *
     * @param email email à vérifier
     * @return {@code true} si un compte utilise déjà cet email
     */
    boolean existsByEmail(String email);
}
