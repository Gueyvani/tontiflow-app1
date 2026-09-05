-- V5 : rend TontineConfig exploitable via l'API (decision metier validee) :
-- une seule configuration par tontine, champs obligatoires reellement
-- appliques, contribution_frequency devient un enum strict.
-- Table tontine_config vide a ce jour (aucun mecanisme de creation
-- n'existait avant cette phase) : alterations directes sans perte de
-- donnees, meme raisonnement que V3.

ALTER TABLE tontine_config
    ALTER COLUMN tontine_id SET NOT NULL;

ALTER TABLE tontine_config
    ADD CONSTRAINT uk_tontine_config_tontine_id UNIQUE (tontine_id);

ALTER TABLE tontine_config
    ALTER COLUMN contribution_amount SET NOT NULL;

ALTER TABLE tontine_config
    ALTER COLUMN contribution_frequency SET NOT NULL;

ALTER TABLE tontine_config
    ADD CONSTRAINT ck_tontine_config_contribution_frequency
        CHECK (contribution_frequency IN ('DAILY', 'WEEKLY', 'MONTHLY'));
