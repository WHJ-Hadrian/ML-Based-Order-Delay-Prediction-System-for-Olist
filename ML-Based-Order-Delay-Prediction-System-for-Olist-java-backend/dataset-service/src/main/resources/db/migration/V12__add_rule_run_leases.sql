ALTER TABLE rule_runs ADD COLUMN claim_token CHAR(36);
ALTER TABLE rule_runs ADD COLUMN claimed_by VARCHAR(128);
ALTER TABLE rule_runs ADD COLUMN lease_expires_at DATETIME(6);
ALTER TABLE rule_runs ADD COLUMN execution_attempt INT NOT NULL DEFAULT 0;

CREATE INDEX idx_rule_runs_claimable
    ON rule_runs (status, mode, lease_expires_at, created_at);
