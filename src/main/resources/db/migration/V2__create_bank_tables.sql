-- The fake bank that the AI agent investigates.
-- Four tables, one per question an on-call engineer asks:
--   services             -> what services exist?
--   app_logs             -> what errors are showing?   (our pretend Kibana)
--   deployments          -> what changed recently?
--   account_applications -> how many customers are affected?

CREATE TABLE services (
    name        VARCHAR(64) PRIMARY KEY,           -- e.g. 'kyc-service'
    description TEXT        NOT NULL               -- what the service does
);

CREATE TABLE deployments (
    id             BIGSERIAL   PRIMARY KEY,        -- 1, 2, 3... assigned automatically
    service        VARCHAR(64) NOT NULL REFERENCES services(name),
    version        VARCHAR(32) NOT NULL,           -- e.g. '2.3.0'
    deployed_at    TIMESTAMPTZ NOT NULL,           -- when it went live
    change_summary TEXT        NOT NULL            -- what changed (config changes too)
);

CREATE TABLE app_logs (
    id        BIGSERIAL   PRIMARY KEY,
    logged_at TIMESTAMPTZ NOT NULL,                -- when it happened
    service   VARCHAR(64) NOT NULL REFERENCES services(name),
    level     VARCHAR(8)  NOT NULL,                -- INFO, WARN or ERROR
    message   TEXT        NOT NULL                 -- the log line itself
);

CREATE TABLE account_applications (
    id             BIGSERIAL   PRIMARY KEY,        -- one row per customer attempt
    status         VARCHAR(16) NOT NULL,           -- APPROVED, FAILED, REJECTED, STUCK
    failure_reason VARCHAR(32),                    -- empty when approved
    created_at     TIMESTAMPTZ NOT NULL
);

-- Indexes make searches fast, like the index at the back of a book.
-- The AI will mostly search logs by service + time, so we index exactly that.
CREATE INDEX idx_app_logs_service_time ON app_logs (service, logged_at);
CREATE INDEX idx_app_logs_level_time   ON app_logs (level, logged_at);
CREATE INDEX idx_deployments_time      ON deployments (deployed_at);
CREATE INDEX idx_applications_time     ON account_applications (created_at);
