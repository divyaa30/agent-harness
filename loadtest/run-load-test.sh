#!/usr/bin/env bash
# Load test: RATE requests/second for DURATION against the app running with the fake model.
# Needs: docker compose up -d, and ./mvnw package -DskipTests.
#   RATE=5 DURATION=60s FAKE_LATENCY=300ms loadtest/run-load-test.sh
source "$(dirname "$0")/common.sh"

RATE="${RATE:-5}"
DURATION="${DURATION:-60s}"

start_app
SINCE=$(sql "select now()")
echo "Load: $RATE runs/s for $DURATION, fake model latency ${FAKE_LATENCY:-300ms} per call"

docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 -e RATE="$RATE" -e DURATION="$DURATION" \
  --add-host=host.docker.internal:host-gateway grafana/k6:0.54.0 run --quiet - < "$ROOT/loadtest/k6-runs.js" \
  | tee "$ROOT/target/k6-summary.txt" | grep -E "http_req_duration|http_req_failed|checks|iterations" || true

wait_until_drained "$SINCE"
echo
report "$SINCE"
kill_app_hard
