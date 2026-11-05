ALTER TABLE workflow_runs ADD COLUMN execution_engine VARCHAR(16) NOT NULL DEFAULT 'temporal';
ALTER TABLE workflow_runs ADD COLUMN execution_lease_owner VARCHAR(100) NULL;
ALTER TABLE workflow_runs ADD COLUMN execution_lease_until TIMESTAMP(6) NULL;

CREATE INDEX idx_workflow_runs_claim ON workflow_runs(execution_engine, status, execution_lease_until, created_at);
