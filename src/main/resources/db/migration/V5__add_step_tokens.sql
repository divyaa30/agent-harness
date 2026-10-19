-- Tokens used by the model call that produced each assistant step.
-- Summing them gives correct run totals even when a run resumes after a crash.
ALTER TABLE agent_steps ADD COLUMN tokens INT NOT NULL DEFAULT 0;
