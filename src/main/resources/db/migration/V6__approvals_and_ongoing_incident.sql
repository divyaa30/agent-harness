-- ============ Human approval for risky tool calls ============
-- One row per risky tool call the agent asked for. The run pauses until a human decides.
CREATE TABLE tool_approvals (
    tool_call_id VARCHAR(128) PRIMARY KEY,                 -- the model's id for that tool call
    run_id       UUID         NOT NULL REFERENCES agent_runs (id),
    tool_name    VARCHAR(64)  NOT NULL,
    arguments    TEXT         NOT NULL,                    -- what exactly it wants to do
    status       VARCHAR(16)  NOT NULL,                    -- PENDING, APPROVED, REJECTED
    decided_by   VARCHAR(64),
    reason       TEXT,
    requested_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    decided_at   TIMESTAMPTZ
);
CREATE INDEX idx_tool_approvals_run ON tool_approvals (run_id);

-- ============ Idempotent writes ============
-- A rollback records the tool call id that caused it. UNIQUE means the same request can
-- never be applied twice, even if a crash makes the agent run the tool again.
ALTER TABLE deployments ADD COLUMN idempotency_key VARCHAR(128) UNIQUE;


-- ============ Incident 6: still ongoing at the end of the day ============
-- Answer key: 19:30 notification-service 1.9.0 moved welcome emails to a new template
-- engine; every welcome email fails from then on, and nobody has rolled it back yet.
-- Account opening itself still succeeds, so application stats look normal: the agent
-- has to find this in the logs. The right fix is a rollback to 1.8.0.
INSERT INTO deployments (service, version, deployed_at, change_summary) VALUES
('notification-service', '1.9.0', '2026-10-15 19:30+00', 'Moved welcome emails to the new template engine');

DELETE FROM app_logs
WHERE service = 'notification-service' AND level = 'INFO'
  AND logged_at >= '2026-10-15 19:30+00';

INSERT INTO app_logs (logged_at, service, level, message) VALUES
('2026-10-15 19:30:00+00', 'notification-service', 'INFO', 'Starting notification-service v1.9.0');
INSERT INTO app_logs (logged_at, service, level, message)
SELECT ts, 'notification-service', 'ERROR',
       format('Welcome email failed for application %s: TemplateRenderException: variable ''customerName'' not found in template welcome-v2',
              90000 + extract(epoch FROM ts)::bigint % 10000)
FROM generate_series(timestamptz '2026-10-15 19:30:20+00', timestamptz '2026-10-15 20:00:00+00', interval '20 seconds') AS ts;
