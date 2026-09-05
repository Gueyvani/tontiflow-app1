CREATE TABLE IF NOT EXISTS tontine_round (
    id BIGSERIAL PRIMARY KEY,
    tontine_id BIGINT NOT NULL,
    beneficiary_id BIGINT,
    round_number INT NOT NULL,
    amount DECIMAL(19,4) NOT NULL,
    start_date TIMESTAMP NOT NULL,
    end_date TIMESTAMP NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PLANNED',
    CONSTRAINT uk_tontine_round UNIQUE (tontine_id, round_number)
    );

CREATE TABLE IF NOT EXISTS round_rotation_history (
    id BIGSERIAL PRIMARY KEY,
    round_id BIGINT NOT NULL,
    previous_beneficiary_id BIGINT,
    new_beneficiary_id BIGINT,
    old_order INT,
    new_order INT,
    reason VARCHAR(512) NOT NULL,
    updated_by VARCHAR(128) NOT NULL,
    timestamp TIMESTAMP NOT NULL,
    modification_type VARCHAR(64) NOT NULL
    );