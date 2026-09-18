-- V5 : table account_status_change, audit durable des transitions administratives
-- du statut d'un compte (decision R21-RD, D7). Append-only : aucune UPDATE/DELETE
-- n'est jamais executee par l'application (AccountStatusChangeRepository n'expose
-- que save() en insertion et une lecture par compte).

CREATE TABLE IF NOT EXISTS account_status_change (
    id               UUID PRIMARY KEY,
    account_id       UUID NOT NULL,
    actor_account_id UUID NOT NULL,
    old_status       VARCHAR(32) NOT NULL,
    new_status       VARCHAR(32) NOT NULL,
    reason           VARCHAR(255) NOT NULL,
    created_at       TIMESTAMP NOT NULL,
    CONSTRAINT fk_account_status_change_account FOREIGN KEY (account_id) REFERENCES auth_account (id),
    CONSTRAINT fk_account_status_change_actor FOREIGN KEY (actor_account_id) REFERENCES auth_account (id)
);

-- Utilise pour retrouver l'historique des transitions d'un compte donne.
CREATE INDEX IF NOT EXISTS idx_account_status_change_account_id ON account_status_change (account_id);
