ALTER TABLE transactions ADD COLUMN provider VARCHAR(64) NOT NULL DEFAULT 'simulated';
ALTER TABLE transactions ALTER COLUMN provider DROP DEFAULT;

ALTER TABLE transactions ADD COLUMN provider_reference VARCHAR(255);
