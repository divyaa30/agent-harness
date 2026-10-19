# Agent Harness

A durable runtime for AI agents, built around a **production-support agent** that investigates
incidents the way an on-call engineer does: it checks the customer impact, searches the logs,
looks at recent deployments, and reports a root cause with evidence.

The model only decides **what to check**. The harness (Java code) runs every check, records
every step, and enforces the limits. That runtime is the focus of the project: making AI agents
reliable, observable and safe enough for backend systems.

> **Status:** in active development. The agent, its tools, step persistence, asynchronous
> execution through Kafka, crash recovery, retries, budgets, loop detection, a human approval
> gate for risky actions and live progress streaming are done. Automated tests and an
> evaluation suite are next. See the [roadmap](#roadmap).

---

## Example

```http
POST /runs
{ "incident": "Many customers got stuck during OTP on 15 October 2026 in the afternoon. Investigate." }
```

The agent investigates on its own:

```
Step 1  get_application_stats  15 Oct 12:00–17:00     → 45 applications STUCK (OTP_PENDING) at 16:00
Step 2  search_logs            otp-service, ERROR      → "OTP delivery delayed by Ns" ×22, 16:02–16:44
Step 3  search_logs            otp-service, all levels → 15:58 WARN "Kafka broker unreachable",
                                                          15:59 "1 of 4 consumers active", lag growing
Step 4  get_recent_deploys     otp-service             → no deployments
```

> **Root cause:** A Kafka broker outage caused the otp-service consumer to fall behind on the
> otp-events topic, delaying OTP delivery. 45 applications were stuck between 16:00 and 16:46 UTC.
> No deployment was involved.

`POST /runs` replies immediately with `202 Accepted` and a run id. A background worker does
the investigation. `GET /runs/{id}/stream` shows each step live as it happens, and
`GET /runs/{id}` shows the status and the full trace.

When the problem is **still ongoing** and a deployment is clearly the cause, the agent proposes
a fix, and the run **pauses until a human approves it**:

```
Step 5  get_recent_deploys    notification-service 1.9.0 at 19:30, nothing since
⏸       rollback_deployment   notification-service → 1.8.0   status: WAITING_APPROVAL
        POST /runs/{id}/approve {"decidedBy": "divya"}
Step 6  (approved by divya)   ROLLED BACK notification-service to 1.8.0
```

---

## How it works

```mermaid
flowchart LR
    U[Client] -->|POST /runs| API[Run API]
    API -->|1. save run PENDING| DB[(agent_runs<br/>agent_steps)]
    API -->|2. publish run id| K[[Kafka<br/>agent-runs]]
    API -.->|3. 202 Accepted| U
    K --> W[Run worker]
    W -->|load saved steps,<br/>resume if any| DB
    W --> LOOP[Agent loop]
    LOOP <-->|messages + tool menu| LLM[LLM provider<br/>OpenAI-compatible]
    LOOP -->|runs requested tools| TR[Tool registry]
    TR --> T1[get_application_stats]
    TR --> T2[search_logs]
    TR --> T3[get_recent_deploys]
    TR --> T4[rollback_deployment<br/>needs approval]
    T1 & T2 & T3 & T4 --> BANK[(Fake bank data<br/>Postgres)]
    LOOP -->|every step, as it happens| DB
    LOOP -.->|risky call: pause| H{{Human:<br/>approve / reject}}
    H -.->|decision re-queues the run| K
    DB -.->|live steps| SSE[GET /runs/id/stream]
    W -.->|commit offset<br/>only when finished| K
```

### The agent loop

```
conversation = [instructions, incident report]
repeat (at most 10 model calls):
    reply = model(conversation, tool menu)
    save reply
    if reply has no tool requests → it's the final answer, stop
    for each requested tool:
        result = run the tool (in Java, with validated, parameterised queries)
        save result; add it to the conversation
```

- **The model never touches the database.** It returns a structured tool request, and the
  harness decides what actually runs. Tool inputs are treated as untrusted and bound as SQL
  parameters.
- **Every message is saved the moment it happens**, each in its own transaction. The saved
  steps *are* the conversation, which is what makes replay, audit and (next) crash recovery
  possible.
- **Tool errors don't crash the run.** They go back to the model as text, so it can correct
  itself.
- **Hard limits:** see [Resilience and limits](#resilience-and-limits).

### Resilience and limits

| Mechanism | What it does |
|---|---|
| **Retry with backoff** | `RetryingLlmClient` wraps the real client (decorator pattern) and retries `429`, `5xx` and timeouts up to 5 times with exponential backoff and jitter, never waiting less than the provider asks (`Retry-After` or "try again in …"). `401` and `400` fail immediately. Three back-to-back runs that used to fail with `429` on the free tier now all complete. |
| **Model-call limit** | At most 10 model calls per run, counting calls made before a crash |
| **Token budget** | 30,000 tokens per run by default, including tokens spent before a crash. Checked before each model call, so a run can overshoot by at most one call |
| **Time budget** | 3 minutes per processing attempt by default |
| **Loop detection** | An exact repeat of an earlier tool call (same tool, same inputs, keys sorted) isn't run again. The model is nudged to try something else, and a third repeat stops the run |
| **Stuck-run sweeper** | Saving a run and publishing it to Kafka are two writes. If the app dies between them, `RunSweeper` re-queues runs left `PENDING` for over 2 minutes. This is safe because processing is idempotent (a lighter alternative to the outbox pattern) |
| **Other limits** | 60s timeout per model call; tool output capped at 15 lines |

When a limit is hit, the run ends as `FAILED` with the reason, for example
`Stopped: used 3378 tokens, over the budget of 3000`.

### Human approval for risky actions

Every tool except one only reads. `rollback_deployment` changes production, so it is marked
**requires approval**:

1. When the model asks for it, the harness doesn't run it. It records an approval request,
   marks the run `WAITING_APPROVAL`, and frees the worker. Nobody blocks while a human decides,
   so a decision can take hours without upsetting Kafka.
2. `POST /runs/{id}/approve` or `/reject` (a reason is required to reject) records the decision
   with a conditional update, so only one decision can win and a second click gets `409 Conflict`.
   It then re-queues the run.
3. The worker resumes the run, which is the same resume logic used after a crash. An approved
   call runs. For a rejected one, the model is told it was rejected and why, and writes its
   report with an alternative.

**Idempotent writes.** The rollback stores the model's tool call id as a unique key
(`INSERT … ON CONFLICT (idempotency_key) DO NOTHING`). If a crash makes the harness run the
same approved call again, the second attempt changes nothing. The tool also refuses to roll back
to a version the service never ran.

Verified: approve (rollback applied once, run completed), second approve (`409`), replaying the
same rollback (`INSERT 0 0`, still one rollback), reject without a reason (`400`), and reject
with a reason (no rollback; the agent's report adopted the team's alternative fix).

### Live progress

`GET /runs/{id}/stream` uses Server-Sent Events. It replays the steps saved so far, then pushes
every new step and status change the moment it's saved, and closes when the run completes or
fails. Watchers are held in memory, so this serves clients connected to the instance running the
worker. Several instances would need a shared channel (a Kafka topic or Redis pub/sub) in between.

### Asynchronous runs and crash recovery

`POST /runs` saves the run as `PENDING`, publishes its id to the `agent-runs` Kafka topic
(waiting for the broker's acknowledgement) and returns `202 Accepted` in milliseconds. A
`@KafkaListener` worker picks the run up and runs the agent loop.

The worker commits the Kafka offset **manually, only after a run is fully processed**. If the
worker dies mid-investigation, the offset is never committed and Kafka redelivers the message
(at-least-once delivery). Processing is therefore designed to be safe to repeat:

- a run that is already `COMPLETED` or `FAILED` is skipped (duplicate delivery)
- a run with saved steps **resumes**: the conversation is rebuilt from `agent_steps`, any tool
  requests that have no saved result are run, and the loop continues from there. Token totals
  stay correct because tokens are stored per step.
- a run with no saved steps starts fresh

**Kafka tracks *which* runs are unfinished; Postgres tracks *how far* each one got.**

Verified by killing the app (hard kill, no graceful shutdown) right after step 2 of an
investigation. On restart, Kafka redelivered the run, the worker logged
`Resumed from 6 saved messages (2 model calls, 2 tool calls already done)`, and it continued
at step 3 and completed, with no lost or repeated work.

### The fake bank

Real production data can't be used, so the project includes a deterministic **fake bank**
(Flyway migrations V2–V3): one busy day of an account-opening journey across five
microservices, with ~11,700 log lines, ~1,400 customer applications and the deployment history.

Six incidents of different kinds are planted in it, plus a red-herring deployment:

| Type | Clues the agent must connect |
|---|---|
| Bad deployment | New exceptions start right after a deploy, stop after the rollback |
| Database connection pool exhaustion | A batch job starts, then connection timeouts |
| External API outage | Upstream timeouts, with no change on our side |
| Kafka broker outage | Broker warning → consumer lag → delayed OTPs |
| Configuration typo | A config change, then valid customers rejected |
| Bad deployment, **still ongoing** | Errors start with a deploy and never stop. Account stats look normal, so only the logs show it. The right fix is a rollback |

The data is generated without randomness, so the agent can be evaluated against a known
answer key, with the same exam every time.

---

## Tech stack

| Area | Technology |
|---|---|
| Language / framework | Java 17, Spring Boot 4 |
| Persistence | PostgreSQL 17, Spring Data JPA, Flyway migrations |
| Messaging | Apache Kafka (KRaft mode), Spring Kafka, manual offset commits |
| LLM | Any OpenAI-compatible chat-completions API (Groq in development), with tool calling |
| HTTP client | Spring `RestClient` with timeouts |
| Infrastructure | Docker Compose |
| Coming next | Testcontainers, Micrometer + Prometheus + Grafana, k6 |

The LLM provider sits behind an `LlmClient` interface. Switching providers is a configuration
change (base URL, model, key), and a different API format is one new class.

---

## Running locally

**Prerequisites:** Java 17, Docker, and an API key for an OpenAI-compatible provider
(for example a free [Groq](https://console.groq.com) key).

```bash
# 1. Start Postgres and Kafka
docker compose up -d

# 2. Provide the API key as an environment variable (never commit it)
#    Windows: set a user environment variable LLM_API_KEY
#    macOS/Linux:
export LLM_API_KEY=your-key

# 3. Run the app (Flyway creates and seeds the database on first start)
./mvnw spring-boot:run
```

Optional settings (environment variables): `LLM_BASE_URL`, `LLM_MODEL`, `DB_URL`, `DB_USER`,
`DB_PASSWORD`, `KAFKA_BOOTSTRAP_SERVERS`, `AGENT_MAX_TOKENS`, `AGENT_MAX_DURATION`, and
`AGENT_STEP_DELAY` (for example `8s`, which slows runs down so you can kill the app mid-run
and watch it resume).

### API

| Method | Path | Description |
|---|---|---|
| `POST` | `/runs` | Queue an investigation. Body: `{"incident": "..."}`. Returns `202 Accepted` with the run id |
| `GET` | `/runs/{id}` | A run's status (`PENDING`, `RUNNING`, `WAITING_APPROVAL`, `COMPLETED`, `FAILED`), final answer, token usage, approval requests and every step |
| `GET` | `/runs/{id}/stream` | Live progress (Server-Sent Events): saved steps, then each new step and status change |
| `POST` | `/runs/{id}/approve` | Approve the risky action a run is waiting on. Body: `{"decidedBy": "...", "reason": "..."}` |
| `POST` | `/runs/{id}/reject` | Reject it. Body: `{"decidedBy": "...", "reason": "..."}` (reason required) |
| `GET` | `/actuator/health` | Health check |

```bash
curl -X POST http://localhost:8080/runs \
  -H 'Content-Type: application/json' \
  -d '{"incident": "Customers could not open accounts on 15 October 2026 in the morning. Investigate."}'
```

---

## Project structure

```
src/main/java/com/divya/agentharness/
├── agent/   AgentLoop: the ask → run tools → repeat loop, budgets, loop detection, and the
│            investigation instructions
├── llm/     LlmClient interface, OpenAI-compatible client, RetryingLlmClient (backoff),
│            message and tool-call types
├── tools/   The three read-only investigation tools, rollback_deployment (approval-gated,
│            idempotent), the tool registry and the service catalog
└── run/     Run and step persistence (JPA), RunService (queue, process, resume),
             RunWorker (Kafka consumer), RunSweeper (re-queues stuck runs),
             ApprovalService (human decisions), RunEvents (SSE), the agent-runs
             topic and the /runs REST API

src/main/resources/db/migration/
├── V1  agent_runs table
├── V2  fake bank tables (services, deployments, app_logs, account_applications)
├── V3  one day of fake bank data with five planted incidents
├── V4  agent_steps table and run token totals
├── V5  tokens per step (correct totals across a resume)
└── V6  tool_approvals, idempotency keys on deployments, and the ongoing sixth incident
```

---

## What I learned improving the agent

Each failure was found by reading the saved step trace, then fixed with a **general
investigation principle**. No incident-specific hints were added.

| Observed failure | Fix |
|---|---|
| The agent invented a service name that doesn't exist | The real service catalog is loaded into the instructions |
| It wandered across dates and reported the day's biggest incident, not the reported one | Stay within the reported date and time of day; low temperature (0.1) for consistency |
| It found the symptom ("OTP delayed") and guessed the cause | Search all log levels shortly before the errors begin; separate symptom from cause |
| `429 Too Many Requests` from the provider's rate limit failed whole runs | Retry with exponential backoff that respects the provider's suggested wait |
| Grouping logs by raw message failed (every line had a unique ID) | `search_logs` normalises numbers into patterns. Trade-off: it also masks useful numbers like broker IDs |
| Searched for "timeout" when the log said "timed out", found nothing, and blamed the wrong incident | Open: a search-quality issue to be measured and fixed with the evaluation suite |
| Diagnosed the ongoing incident correctly but only *suggested* a rollback in its text | Instructions now say to call the tool, since proposing it is safe because a human approves |
| Once, it kept checking for later fixes and hit the 10-call limit | The limit stopped it cleanly. Open: over-caution to be measured in the evaluation suite |

To guard against overfitting the instructions to the known incidents, the evaluation suite
(planned) will also score the agent on held-out incident types it was never tuned on.

---

## Roadmap

- [x] Spring Boot + Postgres + Flyway foundation
- [x] Fake bank with five planted incidents and a red herring
- [x] Provider-agnostic LLM client with tool calling
- [x] Three investigation tools and the agent loop
- [x] Every run and step saved; `POST /runs` and `GET /runs/{id}`
- [x] **Asynchronous runs via Kafka**: `POST /runs` returns immediately, and workers process runs
- [x] **Crash recovery**: a run interrupted mid-investigation resumes from its last saved step
- [x] Retries with exponential backoff and jitter for temporary LLM errors
- [x] Token and time budgets per run
- [x] Loop detection for repeated tool calls (automated test coming with the test suite)
- [x] Sweeper for runs stuck between the database and Kafka
- [x] Human approval gate for risky actions, with idempotent execution of write tools
- [x] Live progress streaming (SSE)
- [ ] Integration tests with Testcontainers and a fake LLM
- [ ] Evaluation suite: accuracy on tuned and held-out incidents, steps and tokens per run
- [ ] Metrics dashboard, load test and chaos test (killing workers under load)
