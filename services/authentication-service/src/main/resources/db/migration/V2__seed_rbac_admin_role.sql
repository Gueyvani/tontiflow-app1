-- V2 : seed du role ROLE_ADMIN, necessaire au bootstrap du premier administrateur RBAC.
-- Aucune nouvelle table : reutilise la structure "role" deja creee par V1.
-- Aucun compte n'est cree ici : l'attribution effective a un compte se fait au
-- demarrage via RbacAdminBootstrapRunner, pilotee par la propriete
-- rbac.bootstrap.admin-email (aucune valeur par defaut, desactivee si absente).
-- Idempotent via ON CONFLICT (name), aligne sur la contrainte uk_role_name (V1).

INSERT INTO role (id, name)
VALUES ('00000000-0000-0000-0000-000000000001', 'ROLE_ADMIN')
ON CONFLICT (name) DO NOTHING;
