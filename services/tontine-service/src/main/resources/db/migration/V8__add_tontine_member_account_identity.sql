-- V8 : introduit l'identite de compte des membres de tontine (decisions R18).
--
-- Ajout purement additif, non destructif, sur un schema deja en place :
--   - status  : liaison du membre a un compte TontiFlow (PENDING / ACTIVE).
--               Toute ligne existante devient PENDING (aucune ligne existante
--               n'est reliee a un compte authentifiable ; conversion
--               user_id BIGINT -> account_id UUID impossible, espaces
--               d'identifiants disjoints - cf. audit R17/R19-A).
--   - account_id : UUID du compte TontiFlow (= AuthAccount.id = UserProfile.id
--                  = claim JWT sub). NULL tant que le membre est PENDING.
--                  Aucune FK cross-service : identite distribuee par UUID
--                  (pattern database-per-service).
--   - uk_tontine_member_tontine_account : une personne = une seule
--     participation ACTIVE par tontine (decision D3). Plusieurs NULL
--     autorises par PostgreSQL sur une contrainte UNIQUE classique ->
--     plusieurs membres PENDING possibles dans la meme tontine.
--
-- uk_tontine_member_tontine_user (tontine_id, user_id) [V4] est CONSERVEE
-- (deprecation transitoire) : user_id n'est pas supprime dans cette phase.
-- Compatible ddl-auto=validate (status VARCHAR <-> @Enumerated(STRING),
-- account_id UUID <-> java.util.UUID).

ALTER TABLE tontine_member
    ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'PENDING';

ALTER TABLE tontine_member
    ADD CONSTRAINT ck_tontine_member_status CHECK (status IN ('PENDING', 'ACTIVE'));

ALTER TABLE tontine_member
    ADD COLUMN account_id UUID;

ALTER TABLE tontine_member
    ADD CONSTRAINT uk_tontine_member_tontine_account UNIQUE (tontine_id, account_id);
