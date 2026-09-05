-- V4 : cree la table "tontine", agregat racine jusqu'ici absent malgre son
-- utilisation implicite (tontine_id) dans tontine_config/tontine_member/
-- tontine_round. Ajoute les contraintes de cle etrangere correspondantes
-- (jamais posees jusqu'ici, faute de table a referencer) et l'unicite d'un
-- membre par tontine (decision explicite : un utilisateur = une seule
-- adhesion par tontine).
--
-- Champs minimaux (decision explicite) : nom + createur (UUID, identite JWT)
-- + date de creation. Aucun statut de cycle de vie : concept absent du
-- domaine actuel, non invente ici.

CREATE TABLE tontine (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    creator_user_id UUID NOT NULL,
    created_at TIMESTAMP NOT NULL
);

ALTER TABLE tontine_config
    ADD CONSTRAINT fk_tontine_config_tontine FOREIGN KEY (tontine_id) REFERENCES tontine (id);

ALTER TABLE tontine_member
    ADD CONSTRAINT fk_tontine_member_tontine FOREIGN KEY (tontine_id) REFERENCES tontine (id);

ALTER TABLE tontine_member
    ADD CONSTRAINT uk_tontine_member_tontine_user UNIQUE (tontine_id, user_id);

ALTER TABLE tontine_round
    ADD CONSTRAINT fk_tontine_round_tontine FOREIGN KEY (tontine_id) REFERENCES tontine (id);
