-- Fills the fake bank with one busy day (15 Oct 2026, 08:00-20:00 UTC)
-- and hides 5 production incidents in it for the AI agent to find.
-- All data is generated deterministically (no random()), so every run of
-- the app produces exactly the same bank. That matters later for evals.
--
-- Incident answer key (the agent must NOT see this file):
--   1. 10:05-10:52  kyc-service 2.3.0 deploy -> NullPointerException       -> KYC_ERROR failures
--   2. 12:30-13:10  reconciliation batch job -> DB connection pool exhausted -> TIMEOUT failures
--   3. 14:00-14:40  external NSDL PAN API down (no deploy involved)         -> PAN_TIMEOUT failures
--   4. 16:00-16:45  Kafka broker-2 down -> otp-events consumer lag          -> applications STUCK
--   5. 18:00-18:47  config typo kyc.min_age 18 -> 81                        -> REJECTED applicants


-- ============ Part A: the services ============
INSERT INTO services (name, description) VALUES
('account-service',          'Runs the account opening journey: creates and tracks applications'),
('kyc-service',              'Verifies customer identity documents (PAN, Aadhaar)'),
('pan-verification-gateway', 'Calls the external NSDL API to validate PAN numbers'),
('otp-service',              'Sends and verifies OTPs; consumes the otp-events Kafka topic'),
('notification-service',     'Sends welcome emails and SMS to customers');


-- ============ Part B: a normal day of logs ============
-- Every service writes one INFO line every 20 seconds, all day (~10,800 rows).
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
      FROM generate_series(timestamptz '2026-10-15 08:00:00+00',
                           timestamptz '2026-10-15 20:00:00+00',
                           interval '20 seconds') AS ts) AS t
CROSS JOIN services AS s;

-- Harmless warnings all day long. Real systems are noisy; the agent must not chase these.
INSERT INTO app_logs (logged_at, service, level, message)
SELECT ts, 'notification-service', 'WARN', 'SMS gateway slow, retrying (attempt 2)'
FROM generate_series(timestamptz '2026-10-15 08:03:00+00', timestamptz '2026-10-15 20:00:00+00', interval '7 minutes') AS ts;

INSERT INTO app_logs (logged_at, service, level, message)
SELECT ts, 'kyc-service', 'WARN', 'Aadhaar OTP service responded in 2100ms (threshold 2000ms)'
FROM generate_series(timestamptz '2026-10-15 08:05:00+00', timestamptz '2026-10-15 20:00:00+00', interval '11 minutes') AS ts;


-- ============ Part C: customers opening accounts ============
-- One application every 30 seconds (~1,440 rows). Normally ~3% are rejected for
-- validation errors; inside each incident window most of them fail.
WITH attempts AS (
    SELECT ts, row_number() OVER (ORDER BY ts) AS n
    FROM generate_series(timestamptz '2026-10-15 08:00:00+00',
                         timestamptz '2026-10-15 20:00:00+00',
                         interval '30 seconds') AS ts
), outcome AS (
    SELECT ts,
           CASE
               WHEN ts >= '2026-10-15 10:05+00' AND ts < '2026-10-15 10:52+00' AND n % 10 < 7 THEN 'KYC_ERROR'
               WHEN ts >= '2026-10-15 12:30+00' AND ts < '2026-10-15 13:10+00' AND n % 10 < 6 THEN 'TIMEOUT'
               WHEN ts >= '2026-10-15 14:00+00' AND ts < '2026-10-15 14:40+00' AND n % 10 < 8 THEN 'PAN_TIMEOUT'
               WHEN ts >= '2026-10-15 16:00+00' AND ts < '2026-10-15 16:45+00' AND n % 10 < 5 THEN 'OTP_PENDING'
               WHEN ts >= '2026-10-15 18:00+00' AND ts < '2026-10-15 18:47+00' AND n % 10 < 9 THEN 'AGE_BELOW_MINIMUM'
               WHEN n % 33 = 0 THEN 'VALIDATION_ERROR'
           END AS reason
    FROM attempts
)
INSERT INTO account_applications (status, failure_reason, created_at)
SELECT CASE
           WHEN reason IS NULL                                    THEN 'APPROVED'
           WHEN reason = 'OTP_PENDING'                            THEN 'STUCK'
           WHEN reason IN ('AGE_BELOW_MINIMUM', 'VALIDATION_ERROR') THEN 'REJECTED'
           ELSE                                                        'FAILED'
       END,
       reason,
       ts
FROM outcome;


-- ============ Part D: deployment history ============
-- A few normal deploys earlier in the week, the culprits, and one red herring
-- (notification-service at 13:55 is harmless, just before incident 3).
INSERT INTO deployments (service, version, deployed_at, change_summary) VALUES
('account-service',          '3.0.4', '2026-10-13 11:20+00', 'Added audit fields to application API'),
('otp-service',              '4.2.0', '2026-10-13 15:45+00', 'Upgraded Kafka client library'),
('notification-service',     '1.7.3', '2026-10-14 09:10+00', 'Fixed SMS template typo'),
('pan-verification-gateway', '2.0.1', '2026-10-14 16:30+00', 'Improved NSDL timeout logging'),
('kyc-service',              '2.3.0', '2026-10-15 10:05+00', 'Refactored PAN validation into new PanValidator class'),
('kyc-service',              '2.2.4', '2026-10-15 10:52+00', 'Rollback to 2.2.4'),
('notification-service',     '1.8.0', '2026-10-15 13:55+00', 'Updated welcome email templates'),
('account-service',          '3.1.1', '2026-10-15 18:00+00', 'Config change: kyc.min_age changed from 18 to 81'),
('account-service',          '3.1.2', '2026-10-15 18:47+00', 'Config fix: kyc.min_age reverted to 18');


-- ============ Part E: the 5 incidents in the logs ============

-- Incident 1: bad deploy of kyc-service 2.3.0
INSERT INTO app_logs (logged_at, service, level, message) VALUES
('2026-10-15 10:05:00+00', 'kyc-service', 'INFO', 'Starting kyc-service v2.3.0'),
('2026-10-15 10:52:10+00', 'kyc-service', 'INFO', 'Starting kyc-service v2.2.4');
INSERT INTO app_logs (logged_at, service, level, message)
SELECT ts, 'kyc-service', 'ERROR',
       format('KYC check failed for application %s: java.lang.NullPointerException at PanValidator.validate(PanValidator.java:42)',
              50000 + extract(epoch FROM ts)::bigint % 10000)
FROM generate_series(timestamptz '2026-10-15 10:05:30+00', timestamptz '2026-10-15 10:51:30+00', interval '20 seconds') AS ts;

-- Incident 2: a batch job hogs the database, the connection pool runs dry
INSERT INTO app_logs (logged_at, service, level, message) VALUES
('2026-10-15 12:28:00+00', 'account-service', 'INFO', 'Started ad-hoc reconciliation batch job (full scan of account_applications)'),
('2026-10-15 13:10:30+00', 'account-service', 'INFO', 'Reconciliation batch job finished in 42m');
INSERT INTO app_logs (logged_at, service, level, message)
SELECT ts, 'account-service', 'WARN', 'Slow query on account_applications took 8.4s'
FROM generate_series(timestamptz '2026-10-15 12:29:00+00', timestamptz '2026-10-15 13:08:00+00', interval '1 minute') AS ts;
INSERT INTO app_logs (logged_at, service, level, message)
SELECT ts, 'account-service', 'ERROR', 'HikariPool-1 - Connection is not available, request timed out after 30000ms'
FROM generate_series(timestamptz '2026-10-15 12:30:00+00', timestamptz '2026-10-15 13:10:00+00', interval '15 seconds') AS ts;

-- Incident 3: the external NSDL PAN API is down (our code is fine)
INSERT INTO app_logs (logged_at, service, level, message)
SELECT ts, 'pan-verification-gateway', 'ERROR', 'NSDL PAN API call timed out after 5000ms'
FROM generate_series(timestamptz '2026-10-15 14:00:00+00', timestamptz '2026-10-15 14:40:00+00', interval '15 seconds') AS ts;
INSERT INTO app_logs (logged_at, service, level, message)
SELECT ts, 'kyc-service', 'ERROR',
       format('PAN verification failed for application %s: upstream timeout from pan-verification-gateway',
              60000 + extract(epoch FROM ts)::bigint % 10000)
FROM generate_series(timestamptz '2026-10-15 14:00:30+00', timestamptz '2026-10-15 14:40:00+00', interval '1 minute') AS ts;
INSERT INTO app_logs (logged_at, service, level, message) VALUES
('2026-10-15 14:41:00+00', 'pan-verification-gateway', 'INFO', 'NSDL PAN API responding normally again');

-- Incident 4: a Kafka broker goes down, OTP messages pile up
INSERT INTO app_logs (logged_at, service, level, message) VALUES
('2026-10-15 15:58:00+00', 'otp-service', 'WARN', 'Kafka broker-2 unreachable; consumer group otp-consumers rebalancing'),
('2026-10-15 15:59:00+00', 'otp-service', 'INFO', 'Consumer group otp-consumers rebalanced: 1 of 4 consumers active'),
('2026-10-15 16:46:00+00', 'otp-service', 'INFO', 'Kafka broker-2 back; 4 of 4 consumers active, lag back to 0');
INSERT INTO app_logs (logged_at, service, level, message)
SELECT ts, 'otp-service', 'WARN',
       format('Consumer lag on topic otp-events is %s messages',
              2000 + (extract(epoch FROM ts - timestamptz '2026-10-15 16:00:00+00') / 60)::int * 1000)
FROM generate_series(timestamptz '2026-10-15 16:00:00+00', timestamptz '2026-10-15 16:45:00+00', interval '1 minute') AS ts;
INSERT INTO app_logs (logged_at, service, level, message)
SELECT ts, 'otp-service', 'ERROR',
       format('OTP delivery delayed by %ss for application %s',
              60 + (extract(epoch FROM ts - timestamptz '2026-10-15 16:00:00+00') / 10)::int,
              70000 + extract(epoch FROM ts)::bigint % 10000)
FROM generate_series(timestamptz '2026-10-15 16:02:00+00', timestamptz '2026-10-15 16:45:00+00', interval '2 minutes') AS ts;

-- Incident 5: a config typo rejects every adult
INSERT INTO app_logs (logged_at, service, level, message)
SELECT ts, 'account-service', 'WARN',
       format('Applicant rejected for application %s: age %s below minimum 81',
              80000 + extract(epoch FROM ts)::bigint % 10000,
              22 + extract(epoch FROM ts)::bigint % 30)
FROM generate_series(timestamptz '2026-10-15 18:00:20+00', timestamptz '2026-10-15 18:46:40+00', interval '20 seconds') AS ts;
