#!/usr/bin/env bash
# Shared helpers for the load and chaos tests (Git Bash on Windows, or any bash).
set -euo pipefail

export MSYS_NO_PATHCONV=1
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="$ROOT/target/agent-harness-0.0.1-SNAPSHOT.jar"
APP_LOG="${APP_LOG:-$ROOT/target/loadtest-app.log}"

sql() {
  docker exec agent-harness-postgres psql -U harness -d agent_harness -tAc "$1"
}

start_app() {
  # The fake model: scripted replies after a simulated provider delay, so the test measures
  # the harness, not the provider, and costs no tokens.
  LLM_MODE=fake LLM_FAKE_LATENCY="${FAKE_LATENCY:-300ms}" nohup java -Xmx512m -jar "$JAR" >> "$APP_LOG" 2>&1 &
  for _ in $(seq 1 90); do
    curl -sf -o /dev/null http://localhost:8080/actuator/health && return 0
    sleep 1
  done
  echo "app did not start; see $APP_LOG" >&2
  return 1
}

kill_app_hard() {
  # Like a crash: no graceful shutdown, nothing gets to finish or commit.
  if command -v powershell.exe > /dev/null; then
    powershell.exe -NoProfile -Command '$c = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue; if ($c) { Stop-Process -Id $c.OwningProcess -Force }'
  else
    pkill -9 -f agent-harness-0.0.1-SNAPSHOT.jar || true
  fi
  sleep 2
}

wait_until_drained() {
  local since="$1"
  for _ in $(seq 1 600); do
    local open
    open=$(sql "select count(*) from agent_runs where created_at >= '$since' and status in ('PENDING','RUNNING')")
    [ "$open" = "0" ] && return 0
    sleep 2
  done
  echo "runs did not drain in time" >&2
  return 1
}

report() {
  local since="$1"
  echo "| Measure | Value |"
  echo "| --- | --- |"
  echo "| Runs created | $(sql "select count(*) from agent_runs where created_at >= '$since'") |"
  echo "| Completed | $(sql "select count(*) from agent_runs where created_at >= '$since' and status = 'COMPLETED'") |"
  echo "| Failed | $(sql "select count(*) from agent_runs where created_at >= '$since' and status = 'FAILED'") |"
  echo "| Still pending or running (lost) | $(sql "select count(*) from agent_runs where created_at >= '$since' and status in ('PENDING','RUNNING')") |"
  echo "| Throughput, runs per second | $(sql "select round(count(*) / greatest(extract(epoch from max(updated_at) - min(created_at)), 1)::numeric, 2) from agent_runs where created_at >= '$since' and status = 'COMPLETED'") |"
  echo "| Accepted to finished, p50 (s) | $(sql "select round(percentile_cont(0.5) within group (order by extract(epoch from updated_at - created_at))::numeric, 2) from agent_runs where created_at >= '$since' and status = 'COMPLETED'") |"
  echo "| Accepted to finished, p95 (s) | $(sql "select round(percentile_cont(0.95) within group (order by extract(epoch from updated_at - created_at))::numeric, 2) from agent_runs where created_at >= '$since' and status = 'COMPLETED'") |"
  echo "| Runs with exactly 9 steps (no lost or repeated work) | $(sql "select count(*) from (select run_id from agent_steps s join agent_runs r on r.id = s.run_id where r.created_at >= '$since' group by run_id having count(*) = 9) x") |"
  echo "| Runs with more than 9 steps (repeated work) | $(sql "select count(*) from (select run_id from agent_steps s join agent_runs r on r.id = s.run_id where r.created_at >= '$since' group by run_id having count(*) > 9) x") |"
}
