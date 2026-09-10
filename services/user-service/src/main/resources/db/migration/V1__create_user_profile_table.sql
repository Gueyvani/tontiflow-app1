-- V1 : table user_profile, requise par l'entite UserProfile.
-- Cle primaire = identifiant utilisateur du JWT (claim "sub", UUID genere
-- par authentication-service pour AuthAccount.id) : aucun identifiant
-- distinct n'est genere ici, le profil est indexe directement sur
-- l'identite authentifiee.
-- Schema derive directement du DDL reellement genere par Hibernate (verifie
-- via H2 en test) pour garantir la coherence avec ddl-auto=validate.

CREATE TABLE IF NOT EXISTS user_profile (
    id UUID PRIMARY KEY,
    full_name VARCHAR(255) NOT NULL,
    phone_number VARCHAR(32)
);
