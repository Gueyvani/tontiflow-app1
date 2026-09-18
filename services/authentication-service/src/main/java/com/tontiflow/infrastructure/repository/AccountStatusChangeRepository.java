package com.tontiflow.infrastructure.repository;

import com.tontiflow.domain.model.AccountStatusChange;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Accès en persistance à l'audit durable des transitions de statut de compte
 * ({@link AccountStatusChange}) — décision R21-RD, D7.
 *
 * <p><b>Append-only par construction</b> : cette interface étend
 * {@code org.springframework.data.repository.Repository}, le marqueur de
 * base de Spring Data (aucune méthode fournie par défaut), plutôt que
 * {@code JpaRepository} — qui exposerait {@code deleteById}/{@code delete}
 * et un {@code save} utilisable aussi bien pour une mise à jour que pour une
 * insertion. Seule une méthode d'insertion et une méthode de lecture par
 * compte sont déclarées ci-dessous ; aucune opération de suppression ni de
 * mise à jour n'existe sur ce repository, à aucun niveau.</p>
 */
@org.springframework.stereotype.Repository
public interface AccountStatusChangeRepository extends Repository<AccountStatusChange, UUID> {

    AccountStatusChange save(AccountStatusChange event);

    List<AccountStatusChange> findByAccountId(UUID accountId);
}
