-- V3 : corrige le type de la colonne "status" de tontine_round.
-- V2 l'avait creee en VARCHAR(32), mais l'entite TontineRound.status
-- (enum RoundStatus, sans @Enumerated donc mapping ORDINAL par defaut JPA)
-- exige un entier. Constat reel a l'execution : Hibernate ddl-auto=validate
-- attend SMALLINT (Types#TINYINT), pas VARCHAR.
-- Table vide (aucune ligne inseree depuis V2) : recreation directe de la colonne.

ALTER TABLE tontine_round DROP COLUMN status;
ALTER TABLE tontine_round ADD COLUMN status SMALLINT NOT NULL DEFAULT 0 CHECK (status BETWEEN 0 AND 7);
