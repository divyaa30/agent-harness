-- Totals for each investigation, filled in when it finishes.
ALTER TABLE agent_runs
    ADD COLUMN llm_calls    INT NOT NULL DEFAULT 0,
    ADD COLUMN tool_calls   INT NOT NULL DEFAULT 0,
    ADD COLUMN total_tokens INT NOT NULL DEFAULT 0;

-- Every message of every investigation, in order. Together they ARE the
-- conversation, so a run can be replayed (and, later, resumed after a crash).
CREATE TABLE agent_steps (
    id           BIGSERIAL   PRIMARY KEY,
    run_id       UUID        NOT NULL REFERENCES agent_runs (id),
    seq          INT         NOT NULL,                  -- 1, 2, 3... order within the run
    role         VARCHAR(16) NOT NULL,                  -- system, user, assistant, tool
    content      TEXT,                                  -- the text, if any
    tool_calls   TEXT,                                  -- the model's tool requests, as JSON
    tool_call_id VARCHAR(128),                          -- for tool results: which request this answers
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (run_id, seq)                                -- a run can't have two step 3s
);
