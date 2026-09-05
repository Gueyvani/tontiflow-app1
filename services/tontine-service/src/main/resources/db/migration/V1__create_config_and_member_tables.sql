-- V1 : tables tontine_config et tontine_member, requises par les entites
-- TontineConfig et TontineMember mais jusqu'ici absentes de toute migration
-- (seule V2, couvrant tontine_round/round_rotation_history, existait).
-- Schema derive directement du DDL reellement genere par Hibernate (verifie
-- via H2 en test) pour garantir la coherence avec ddl-auto=validate.

CREATE TABLE IF NOT EXISTS tontine_config (
    id BIGSERIAL PRIMARY KEY,
    tontine_id BIGINT,
    rotation_type SMALLINT NOT NULL CHECK (rotation_type BETWEEN 0 AND 2),
    non_compliant_behavior SMALLINT NOT NULL CHECK (non_compliant_behavior BETWEEN 0 AND 3),
    contribution_amount DECIMAL(38,2),
    contribution_frequency VARCHAR(255),
    max_members INT NOT NULL,
    reorganisation_allowed BOOLEAN NOT NULL
);

CREATE TABLE IF NOT EXISTS tontine_member (
    id BIGSERIAL PRIMARY KEY,
    tontine_id BIGINT,
    user_id BIGINT,
    sequential_order INT NOT NULL,
    active BOOLEAN NOT NULL,
    suspended BOOLEAN NOT NULL,
    excluded BOOLEAN NOT NULL,
    paid_mandatory_contribution BOOLEAN NOT NULL
);
