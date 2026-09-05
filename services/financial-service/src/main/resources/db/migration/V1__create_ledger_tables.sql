-- V1 : socle du Ledger financier (decision R2, Phase R2).
-- financial-service devient proprietaire exclusif du registre financier
-- (aucune autre table de ce service n'existait avant cette migration).

CREATE TABLE financial_account (
    id               UUID PRIMARY KEY,
    owner_reference  BIGINT      NOT NULL,
    account_type     VARCHAR(32) NOT NULL,
    currency         VARCHAR(8)  NOT NULL,
    status           VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at       TIMESTAMP   NOT NULL,
    CONSTRAINT uk_financial_account_owner_type UNIQUE (owner_reference, account_type)
);

CREATE TABLE journal_entry (
    id                  UUID         PRIMARY KEY,
    business_reference  VARCHAR(255) NOT NULL,
    event_type          VARCHAR(64)  NOT NULL,
    idempotency_key     VARCHAR(255) NOT NULL,
    description         VARCHAR(500) NOT NULL,
    created_at          TIMESTAMP    NOT NULL,
    CONSTRAINT uk_journal_entry_idempotency_key UNIQUE (idempotency_key)
);

-- Une ligne est soit un debit, soit un credit, jamais les deux ni aucun des
-- deux (decision R2, S8) : contrainte CHECK, pas seulement une validation
-- applicative - rend une ligne incoherente impossible a persister.
CREATE TABLE ledger_line (
    id                     UUID          PRIMARY KEY,
    journal_entry_id       UUID          NOT NULL REFERENCES journal_entry (id),
    financial_account_id   UUID          NOT NULL REFERENCES financial_account (id),
    debit                  NUMERIC(19,2) NOT NULL,
    credit                 NUMERIC(19,2) NOT NULL,
    currency               VARCHAR(8)    NOT NULL,
    CONSTRAINT ck_ledger_line_non_negative CHECK (debit >= 0 AND credit >= 0),
    CONSTRAINT ck_ledger_line_debit_xor_credit
        CHECK ((debit > 0 AND credit = 0) OR (debit = 0 AND credit > 0))
);

CREATE INDEX idx_ledger_line_financial_account_id ON ledger_line (financial_account_id);
CREATE INDEX idx_ledger_line_journal_entry_id ON ledger_line (journal_entry_id);
