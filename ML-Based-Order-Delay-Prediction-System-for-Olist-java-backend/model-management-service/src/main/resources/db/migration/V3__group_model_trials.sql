ALTER TABLE model_trials ADD comparison_id VARCHAR(36) NULL;

UPDATE model_trials SET comparison_id = id WHERE comparison_id IS NULL;

ALTER TABLE model_trials MODIFY COLUMN comparison_id VARCHAR(36) NOT NULL;

CREATE INDEX idx_model_trials_comparison_owner ON model_trials(comparison_id, owner_id);
