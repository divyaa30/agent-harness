# Demo script (2–3 minutes)

A shot list for recording a short demo video (for example with the Windows Game Bar: Win+G, or
OBS). Each step says what to show and one line to say. Practise once before recording.

## Before recording

```bash
docker compose up -d
./mvnw package -DskipTests
```

- Close IntelliJ and extra browser tabs (Kafka and Postgres need the memory).
- Open three windows side by side: a terminal for the app, Postman, and a browser.
- Start the app with the step delay so the crash demo has time:

```powershell
$env:AGENT_STEP_DELAY = '6s'; java -jar target/agent-harness-0.0.1-SNAPSHOT.jar
```

## 1. The problem (15 s)

Show the README's architecture diagram.

> "An on-call engineer spends the first ten minutes of every incident on the same checks: impact,
> logs, recent deploys. I built a runtime that lets an AI agent do that safely, inside a normal
> backend: Kafka, Postgres, retries, budgets, human approval."

## 2. Start an investigation (30 s)

In Postman: `POST http://localhost:8080/runs`

```json
{ "incident": "Customers could not open accounts on 15 October 2026 in the morning. Investigate." }
```

Point at the `202 Accepted` reply and the run id.

> "The API replies in milliseconds. The investigation runs in a Kafka worker."

In the browser, open `http://localhost:8080/runs/<id>/stream`. Steps appear live.

> "Each step streams live: impact stats, then kyc-service error logs, then deployments."

## 3. Crash it (45 s)

While the stream is still going (after about two steps), kill the app hard from a second
PowerShell window. Ctrl+C would be a graceful shutdown; this is a crash:

```powershell
Stop-Process -Id (Get-NetTCPConnection -LocalPort 8080 -State Listen).OwningProcess -Force
```

> "I just killed the app in the middle of an investigation."

Restart it with the same command. In the log, point at:

```
Resumed from 6 saved messages (2 model calls, 2 tool calls already done)
```

> "Kafka never got the 'done' signal, so it redelivered the run. The worker rebuilt the
> conversation from Postgres and continued at step 3: no lost work, no repeated tool calls."

Open `GET /runs/<id>`: status `COMPLETED`, and the root cause names kyc-service 2.3.0.

## 4. A risky action needs a human (45 s)

Restart without the delay (`$env:AGENT_STEP_DELAY = '0s'`), then:

```json
{ "incident": "Customers who opened accounts on the evening of 15 October 2026 never received a welcome email. It is still happening right now. Investigate." }
```

`GET /runs/<id>` shows `WAITING_APPROVAL` and the proposed `rollback_deployment` of
notification-service to 1.8.0, with the agent's reason.

> "The agent can propose a fix, but anything that changes production waits for a human."

`POST /runs/<id>/approve` with `{"decidedBy": "your-name", "reason": "evidence is clear"}`.
The run resumes, the rollback runs once, and the run completes.

> "The rollback uses the tool call id as an idempotency key, so a crash can't apply it twice."

## 5. The proof (20 s)

Show `evals/` results and the load and chaos test numbers in the README.

> "It's tested with Testcontainers and a scripted fake model, scored on an eval suite with
> held-out incident types, and load- and chaos-tested: killing the app under load lost no runs."
