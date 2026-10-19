# Load and chaos test results (19 Oct 2026)

Setup: one app instance on a laptop (8 GB RAM, Windows, Docker Desktop), Postgres 17 and Kafka 3.9
in Docker, `agent-runs` with 3 partitions and 3 worker threads. The fake model (`llm.mode=fake`)
answers each call after 300 ms and drives a fixed investigation: 3 real tool calls against the fake
bank, then an answer, so 4 model calls and 9 saved steps per run.

## Load test

`RATE=5 DURATION=60s FAKE_LATENCY=300ms loadtest/run-load-test.sh`: 300 runs offered at 5 per
second, about 2.5 times what 3 workers can process.

| Measure | Value |
| --- | --- |
| `POST /runs` accepted | 300 of 300, 0 errors |
| `POST /runs` latency | median 28 ms, p95 38 ms |
| Runs completed | 300 of 300, 0 failed, 0 lost |
| Throughput | 1.99 runs per second |
| Accepted to finished | p50 35.7 s, p95 81.9 s (mostly queue wait, by design under overload) |
| Runs with exactly 9 steps | 300 of 300 |

Reading it:
- The API stays fast under overload because Kafka absorbs the burst; requests queue instead of
  slowing the API.
- Throughput is bounded by workers × model time: 4 calls × 0.3 s = 1.2 s of model time per run,
  so 3 workers can do at most about 2.5 runs/s. The measured 1.99 runs/s puts the harness's own
  overhead (9 database writes, 3 SQL tools, Kafka) at about 0.3 s per run.
- More partitions and instances raise the ceiling; with a real provider, its rate limit is the
  ceiling first.

## Chaos test

`RUNS=200 KILL_AFTER=10 loadtest/chaos-test.sh`: queue 200 runs, hard-kill the app (no graceful
shutdown) 10 seconds in, restart it, and check every run.

| Measure | Value |
| --- | --- |
| At the kill | 39 completed, 158 pending, 3 running mid-investigation |
| After restart: completed | 200 of 200 |
| Failed or lost | 0 |
| Runs with exactly 9 steps (nothing lost or repeated) | 200 of 200 |
| Runs resumed from saved steps | 3, exactly the 3 that were running at the kill |
