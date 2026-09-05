-- Decision R11 (corrections techniques) : round_rotation_history.round_id
-- est interroge par TontineRoundApplicationService.listRotationHistory
-- (findByRoundId, decision R9) depuis la creation de la table (V2), sans
-- jamais avoir eu d'index de support ni de contrainte d'integrite
-- referentielle vers tontine_round(id) - contrairement a tontine_config,
-- tontine_member et tontine_round, qui ont deja leur FK vers tontine (V4).
--
-- N'ajoute, ne modifie et ne supprime aucune donnee existante : ajout pur
-- de contraintes techniques sur un schema deja en place.

CREATE INDEX idx_round_rotation_history_round_id ON round_rotation_history (round_id);

ALTER TABLE round_rotation_history
    ADD CONSTRAINT fk_round_rotation_history_round
        FOREIGN KEY (round_id) REFERENCES tontine_round (id);
