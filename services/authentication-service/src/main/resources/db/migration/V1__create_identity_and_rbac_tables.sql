-- V1 : schema identite & RBAC (Role-Based Access Control) pour authentication-service.
-- Perimetre : authentication_db (base isolee, pattern Database-per-Service).
--
-- auth_account porte les identifiants de connexion (email + hash), independamment
-- du profil metier gere par user-service. Un compte peut porter plusieurs roles,
-- un role peut porter plusieurs permissions (relations many-to-many classiques).

CREATE TABLE IF NOT EXISTS auth_account (
    id            UUID PRIMARY KEY,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    status        VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    CONSTRAINT uk_auth_account_email UNIQUE (email)
);

CREATE TABLE IF NOT EXISTS role (
    id   UUID PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    CONSTRAINT uk_role_name UNIQUE (name)
);

CREATE TABLE IF NOT EXISTS permission (
    id   UUID PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    CONSTRAINT uk_permission_name UNIQUE (name)
);

-- Table de jointure AuthAccount <-> Role
CREATE TABLE IF NOT EXISTS account_role (
    account_id UUID NOT NULL,
    role_id    UUID NOT NULL,
    PRIMARY KEY (account_id, role_id),
    CONSTRAINT fk_account_role_account FOREIGN KEY (account_id) REFERENCES auth_account (id),
    CONSTRAINT fk_account_role_role FOREIGN KEY (role_id) REFERENCES role (id)
);

-- Table de jointure Role <-> Permission
CREATE TABLE IF NOT EXISTS role_permission (
    role_id       UUID NOT NULL,
    permission_id UUID NOT NULL,
    PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id) REFERENCES role (id),
    CONSTRAINT fk_role_permission_permission FOREIGN KEY (permission_id) REFERENCES permission (id)
);
