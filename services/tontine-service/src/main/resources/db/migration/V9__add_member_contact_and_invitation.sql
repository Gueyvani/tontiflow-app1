-- V9 : contact d'invitation optionnel sur tontine_member + table des
-- invitations de liaison (decisions R20-A / R20-B).
--
-- Additif et non destructif :
--   - display_name / invited_phone : aides a l'invitation, optionnelles,
--     figees a l'ajout du membre. Ne remplacent pas UserProfile
--     (source de verite du profil, cote user-service).
--   - member_invitation : code opaque a usage unique, valable 7 jours.
--     Seul le SHA-256 hex du code (code_hash) est stocke, jamais le code
--     en clair. tontine_member_id est une simple colonne Long avec FK
--     (aucune relation JPA - pas de couplage de chargement).
--
-- N'altere NI ne supprime : user_id, uk_tontine_member_tontine_user,
-- tontine_member.id, account_id, status, financial_account.
-- V1 -> V8 restent inchangees. Compatible ddl-auto=validate.

ALTER TABLE tontine_member ADD COLUMN display_name  VARCHAR(255);
ALTER TABLE tontine_member ADD COLUMN invited_phone VARCHAR(32);

CREATE TABLE member_invitation (
    id                UUID PRIMARY KEY,
    tontine_member_id BIGINT      NOT NULL,
    code_hash         VARCHAR(64) NOT NULL,
    issued_at         TIMESTAMP   NOT NULL,
    expires_at        TIMESTAMP   NOT NULL,
    consumed_at       TIMESTAMP,
    CONSTRAINT uk_member_invitation_code_hash UNIQUE (code_hash),
    CONSTRAINT fk_member_invitation_member
        FOREIGN KEY (tontine_member_id) REFERENCES tontine_member (id)
);

-- Recherche des invitations d'un membre (invalidation des actives, historique).
CREATE INDEX idx_member_invitation_member ON member_invitation (tontine_member_id);

-- Regle metier : au plus UNE invitation active (non consommee) par membre.
-- Index unique partiel PostgreSQL : garantit l'unicite au niveau moteur et
-- serialise deux generations concurrentes pour le meme membre.
CREATE UNIQUE INDEX uk_member_invitation_active
    ON member_invitation (tontine_member_id)
    WHERE consumed_at IS NULL;
