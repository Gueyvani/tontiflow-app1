-- V4 : verrouillage temporise du compte apres echecs d'authentification repetes
-- (decision R21-D.3, option B). Complete le rate limiting IP du Gateway
-- (R21-D.2, 5 requetes/minute/IP sur POST /api/v1/auth/login) par une
-- protection PAR COMPTE, resistante au credential-stuffing distribue
-- (plusieurs IP) et aux attaques lentes contre un compte cible.
--
-- failed_attempts      : nombre d'echecs consecutifs dans la fenetre courante.
-- last_failed_login_at : horodatage du dernier echec, ancre de la fenetre
--   glissante (au-dela, le compteur repart a 1 au lieu de s'incrementer).
-- locked_until          : verrouillage temporise en cours, expirant de
--   lui-meme - NULL = aucun verrouillage actif.
--
-- Ce verrouillage AUTOMATIQUE ne doit jamais etre confondu avec
-- AccountStatus.LOCKED (verrouillage MANUEL/administratif, HTTP 423,
-- inchange par cette migration) : le declenchement automatique reste
-- volontairement indiscernable d'un mot de passe incorrect (HTTP 401
-- generique, cf. AuthAccountService) pour preserver l'anti-enumeration
-- deja en place.

ALTER TABLE auth_account
    ADD COLUMN failed_attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN last_failed_login_at TIMESTAMP,
    ADD COLUMN locked_until TIMESTAMP;
