-- One row per agent run: a user reports an incident, the agent investigates it.
CREATE TABLE agent_runs (
    id          UUID PRIMARY KEY,
    incident    TEXT        NOT NULL,                    -- what the user reported
    status      VARCHAR(32) NOT NULL,                    -- PENDING, RUNNING, COMPLETED, FAILED
    result      TEXT,                                    -- the agent's final answer
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_agent_runs_status ON agent_runs (status);
