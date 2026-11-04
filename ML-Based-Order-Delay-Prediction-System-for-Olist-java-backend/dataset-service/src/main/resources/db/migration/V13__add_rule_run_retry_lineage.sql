ALTER TABLE rule_runs ADD COLUMN retry_of_run_id CHAR(36);

CREATE INDEX idx_rule_runs_retry_of ON rule_runs (retry_of_run_id);
