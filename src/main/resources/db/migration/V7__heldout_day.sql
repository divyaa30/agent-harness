-- A second busy day (14 Oct 2026, 08:00-20:00 UTC) with three NEW kinds of incident.
-- These are the eval suite's held-out set: the agent's instructions were never tuned on
-- them, so its accuracy here shows whether the instructions generalise.
--
-- Held-out answer key (the agent must NOT see this file):
--   H1. 09:00 account-service 3.0.5 "in-memory cache" leaks memory; heap climbs for hours,
--       then OutOfMemoryError + OOMKilled restarts 11:45-12:30 -> SERVICE_UNAVAILABLE.
--       The deploy is ~3h before the errors (a delayed effect). Rolled back to 3.0.4 at 12:35.
--   H2. 14:00 the TLS certificate of the external NSDL API expires -> PKIX errors in
--       pan-verification-gateway until the truststore reload at 15:12. No deploy involved.
--   H3. 17:00 kyc-service 2.2.3 drops "unused" indexes -> full table scans, KYC timeouts
--       until 2.2.4 restores the index at 17:42.


-- ============ A normal day ============
INSERT INTO app_logs (logged_at, service, level, message)
SELECT t.ts, s.name, 'INFO',
       CASE s.name
           WHEN 'account-service'          THEN format('Application %s created', t.app_id)
           WHEN 'kyc-service'              THEN format('KYC verified for application %s', t.app_id)
           WHEN 'pan-verification-gateway' THEN format('PAN verified via NSDL in %sms', 180 + t.app_id % 120)
           WHEN 'otp-service'              THEN format('OTP sent for application %s', t.app_id)
           ELSE                                 format('Welcome email sent for application %s', t.app_id)
       END
FROM (SELECT ts, (extract(epoch FROM ts)::bigint / 20) % 100000 AS app_id
      FROM generate_series(timestamptz '2026-10-14 08:00:00+00',
                           timestamptz '2026-10-14 20:00:00+00',
                           interval '20 seconds') AS ts) AS t
CROSS JOIN services AS s;

INSERT INTO app_logs (logged_at, service, level, message)
SELECT ts, 'notification-service', 'WARN', 'SMS gateway slow, retrying (attempt 2)'
FROM generate_series(timestamptz '2026-10-14 08:03:00+00', timestamptz '2026-10-14 20:00:00+00', interval '7 minutes') AS ts;

INSERT INTO app_logs (logged_at, service, level, message)
SELECT ts, 'kyc-service', 'WARN', 'Aadhaar OTP service responded in 2100ms (threshold 2000ms)'
FROM generate_series(timestamptz '2026-10-14 08:05:00+00', timestamptz '2026-10-14 20:00:00+00', interval '11 minutes') AS ts;

WITH attempts AS (
    SELECT ts, row_number() OVER (ORDER BY ts) AS n
    FROM generate_series(timestamptz '2026-10-14 08:00:00+00',
                         timestamptz '2026-10-14 20:00:00+00',
                         interval '30 seconds') AS ts
), outcome AS (
    SELECT ts,
           CASE
               WHEN ts >= '2026-10-14 11:45+00' AND ts < '2026-10-14 12:30+00' AND n % 10 < 6 THEN 'SERVICE_UNAVAILABLE'
               WHEN ts >= '2026-10-14 14:00+00' AND ts < '2026-10-14 15:12+00' AND n % 10 < 8 THEN 'PAN_TLS_ERROR'
               WHEN ts >= '2026-10-14 17:02+00' AND ts < '2026-10-14 17:42+00' AND n % 10 < 5 THEN 'KYC_TIMEOUT'
               WHEN n % 33 = 0 THEN 'VALIDATION_ERROR'
           END AS reason
    FROM attempts
)
INSERT INTO account_applications (status, failure_reason, created_at)
SELECT CASE
           WHEN reason IS NULL                THEN 'APPROVED'
           WHEN reason = 'VALIDATION_ERROR'   THEN 'REJECTED'
           ELSE                                    'FAILED'
       END,
       reason,
       ts
FROM outcome;


-- ============ Deployments ============
INSERT INTO deployments (service, version, deployed_at, change_summary) VALUES
('account-service', '3.0.5', '2026-10-14 09:00+00', 'Added an in-memory cache for application lookups'),
('account-service', '3.0.4', '2026-10-14 12:35+00', 'Rollback to 3.0.4'),
('kyc-service',     '2.2.3', '2026-10-14 17:00+00', 'Database cleanup: dropped unused indexes on kyc_documents'),
('kyc-service',     '2.2.4', '2026-10-14 17:42+00', 'Restored index idx_kyc_documents_customer');


-- ============ H1: a memory leak with a delayed effect ============
INSERT INTO app_logs (logged_at, service, level, message) VALUES
('2026-10-14 09:00:00+00', 'account-service', 'INFO', 'Starting account-service v3.0.5'),
('2026-10-14 12:35:10+00', 'account-service', 'INFO', 'Starting account-service v3.0.4');
INSERT INTO app_logs (logged_at, service, level, message)
SELECT ts, 'account-service', 'WARN',
       format('Heap usage at %s%% after full GC', 55 + (extract(epoch FROM ts - timestamptz '2026-10-14 09:30:00+00') / 360)::int)
FROM generate_series(timestamptz '2026-10-14 09:30:00+00', timestamptz '2026-10-14 11:40:00+00', interval '10 minutes') AS ts;
INSERT INTO app_logs (logged_at, service, level, message)
SELECT ts, 'account-service', 'ERROR', 'java.lang.OutOfMemoryError: Java heap space in ApplicationLookupCache.put'
FROM generate_series(timestamptz '2026-10-14 11:45:00+00', timestamptz '2026-10-14 12:30:00+00', interval '20 seconds') AS ts;
INSERT INTO app_logs (logged_at, service, level, message) VALUES
('2026-10-14 11:50:00+00', 'account-service', 'WARN', 'Pod account-service-7f9c restarted (reason: OOMKilled)'),
('2026-10-14 12:10:00+00', 'account-service', 'WARN', 'Pod account-service-7f9c restarted (reason: OOMKilled)'),
('2026-10-14 12:28:00+00', 'account-service', 'WARN', 'Pod account-service-7f9c restarted (reason: OOMKilled)');


-- ============ H2: an expired TLS certificate at an external API (no deploy) ============
INSERT INTO app_logs (logged_at, service, level, message) VALUES
('2026-10-14 08:00:00+00', 'pan-verification-gateway', 'WARN', 'TLS certificate for nsdl-api.example expires at 2026-10-14T14:00Z (in 6 hours)'),
('2026-10-14 11:00:00+00', 'pan-verification-gateway', 'WARN', 'TLS certificate for nsdl-api.example expires at 2026-10-14T14:00Z (in 3 hours)'),
('2026-10-14 15:12:00+00', 'pan-verification-gateway', 'INFO', 'Reloaded TLS truststore; certificate for nsdl-api.example valid until 2027-10-14');
INSERT INTO app_logs (logged_at, service, level, message)
SELECT ts, 'pan-verification-gateway', 'ERROR', 'PKIX path validation failed: certificate expired (CN=nsdl-api.example)'
FROM generate_series(timestamptz '2026-10-14 14:00:10+00', timestamptz '2026-10-14 15:11:50+00', interval '15 seconds') AS ts;
INSERT INTO app_logs (logged_at, service, level, message)
SELECT ts, 'kyc-service', 'ERROR',
       format('PAN verification failed for application %s: TLS handshake error from pan-verification-gateway',
              40000 + extract(epoch FROM ts)::bigint % 10000)
FROM generate_series(timestamptz '2026-10-14 14:00:30+00', timestamptz '2026-10-14 15:11:00+00', interval '1 minute') AS ts;


-- ============ H3: a dropped database index ============
INSERT INTO app_logs (logged_at, service, level, message) VALUES
('2026-10-14 17:00:00+00', 'kyc-service', 'INFO', 'Starting kyc-service v2.2.3'),
('2026-10-14 17:00:05+00', 'kyc-service', 'INFO', 'Flyway: applied migration V58__drop_unused_indexes'),
('2026-10-14 17:42:10+00', 'kyc-service', 'INFO', 'Starting kyc-service v2.2.4');
INSERT INTO app_logs (logged_at, service, level, message)
SELECT ts, 'kyc-service', 'WARN', 'Slow query on kyc_documents took 12.4s (sequential scan, 8.1M rows)'
FROM generate_series(timestamptz '2026-10-14 17:01:00+00', timestamptz '2026-10-14 17:41:00+00', interval '1 minute') AS ts;
INSERT INTO app_logs (logged_at, service, level, message)
SELECT ts, 'kyc-service', 'ERROR',
       format('KYC lookup for application %s exceeded 10000ms deadline', 30000 + extract(epoch FROM ts)::bigint % 10000)
FROM generate_series(timestamptz '2026-10-14 17:02:00+00', timestamptz '2026-10-14 17:41:00+00', interval '20 seconds') AS ts;
