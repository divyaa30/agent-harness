#!/usr/bin/env bash
# Chaos test: queue a burst of runs, hard-kill the app while workers are mid-investigation,
# restart it, and check that every run still completes with no lost or repeated work.
# Needs: docker compose up -d, and ./mvnw package -DskipTests.
#   RUNS=200 KILL_AFTER=10 loadtest/chaos-test.sh
source "$(dirname "$0")/common.sh"

RUNS="${RUNS:-200}"
KILL_AFTER="${KILL_AFTER:-10}"
: > "$APP_LOG"

start_app
SINCE=$(sql "select now()")
echo "Queueing $RUNS runs..."
pids=()
for _ in $(seq 1 "$RUNS"); do
  curl -s -o /dev/null -X POST http://localhost:8080/runs -H 'Content-Type: application/json' \
    -d '{"incident":"Chaos test: customers could not open accounts on 15 October 2026 in the morning."}' &
  pids+=($!)
done
wait "${pids[@]}"   # only the requests: a bare `wait` would also wait for the app itself

sleep "$KILL_AFTER"
echo "Killing the app (hard) after ${KILL_AFTER}s..."
echo "At the kill: $(sql "select string_agg(status || '=' || n, ', ') from (select status, count(*) n from agent_runs where created_at >= '$SINCE' group by status) x")"
kill_app_hard

echo "Restarting..."
start_app
wait_until_drained "$SINCE"
echo
report "$SINCE"
echo "| Runs resumed from saved steps after the crash | $(grep -c 'Resumed from' "$APP_LOG" || true) |"
kill_app_hard
