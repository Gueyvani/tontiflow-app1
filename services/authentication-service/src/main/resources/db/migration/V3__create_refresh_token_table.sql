-- V3 : table refresh_token, necessaire au mecanisme de renouvellement de session.
-- token_hash stocke uniquement SHA-256(token brut) : le token en clair n'est jamais persiste.

CREATE TABLE IF NOT EXISTS refresh_token (
    id            UUID PRIMARY KEY,
    account_id    UUID NOT NULL,
    token_hash    VARCHAR(64) NOT NULL,
    family_id     UUID NOT NULL,
    issued_at     TIMESTAMP NOT NULL,
    expires_at    TIMESTAMP NOT NULL,
    revoked_at    TIMESTAMP,
    CONSTRAINT uk_refresh_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_token_account FOREIGN KEY (account_id) REFERENCES auth_account (id)
);

-- Utilise par RefreshTokenRepository.revokeFamily(...) lors de la revocation de toute une lignee.
CREATE INDEX IF NOT EXISTS idx_refresh_token_family_id ON refresh_token (family_id);

-- Utilise pour d'eventuelles recherches/purges par compte.
CREATE INDEX IF NOT EXISTS idx_refresh_token_account_id ON refresh_token (account_id);
