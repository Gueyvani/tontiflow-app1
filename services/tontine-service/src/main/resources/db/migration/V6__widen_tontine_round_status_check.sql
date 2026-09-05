-- V6 : elargit la contrainte CHECK de tontine_round.status pour accepter le
-- nouveau statut RoundStatus.BLOCKED (decision P1, ordinal 8, ajoute en fin
-- d'enum sans reordonner les valeurs existantes 0-7).
-- Nom de contrainte verifie reellement sur PostgreSQL (auto-genere par V3,
-- qui ne la nommait pas explicitement) : tontine_round_status_check.
-- V1-V5 restent inchangees.

ALTER TABLE tontine_round DROP CONSTRAINT tontine_round_status_check;
ALTER TABLE tontine_round ADD CONSTRAINT tontine_round_status_check CHECK (status BETWEEN 0 AND 8);
