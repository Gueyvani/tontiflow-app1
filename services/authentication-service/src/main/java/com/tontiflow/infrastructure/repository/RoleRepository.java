package com.tontiflow.infrastructure.repository;

import com.tontiflow.domain.model.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Accès en persistance aux rôles RBAC ({@link Role}).
 */
@Repository
public interface RoleRepository extends JpaRepository<Role, UUID> {

    Optional<Role> findByName(String name);

    boolean existsByName(String name);

    /**
     * Décision R14-E : charge tous les rôles avec leurs permissions par
     * {@code LEFT JOIN FETCH} en une seule requête, évitant le N+1 déjà
     * démontré (une requête par rôle) dans {@link
     * com.tontiflow.application.service.RoleService#findAll()}. {@code
     * LEFT JOIN} (jamais {@code JOIN} seul) : un rôle sans aucune permission
     * doit rester présent dans le résultat, comportement déjà existant de
     * {@link #findAll()} (méthode héritée, inchangée). {@code DISTINCT} :
     * {@code permissions} est une collection {@code @ManyToMany} — une
     * jointure sur une collection multiplie les lignes SQL (une par
     * permission), contrairement à une jointure vers-un ; {@code DISTINCT}
     * évite qu'un même rôle n'apparaisse plusieurs fois dans la liste
     * retournée.
     */
    @Query("SELECT DISTINCT r FROM Role r LEFT JOIN FETCH r.permissions")
    List<Role> findAllWithPermissions();
}
