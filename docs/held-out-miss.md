# The held-out miss, explained simply

One page on the one held-out incident the agent got wrong: what happened, why, what I changed,
and what is still not proven.

## 1. The short version

The agent found the **right cause** but suggested the **wrong action**. It wanted to roll back a
release that had already been rolled back. The human approval step stopped it. I then added a
rule in code so this kind of rollback is refused before anyone is even asked.

## 2. What actually happened to account-service (the fake bank's data)

| When (UTC) | What happened |
| --- | --- |
| 13 Oct 11:20 | Version 3.0.4 deployed |
| 14 Oct 09:00 | Version 3.0.5 deployed: added an in-memory cache |
| 14 Oct 11:45 to 12:30 | Memory runs out (OutOfMemoryError), the service keeps restarting |
| 14 Oct 12:35 | Someone rolls back to 3.0.4. Problem over |
| 15 Oct 18:00 | Version 3.1.1 deployed (a config change) |
| 15 Oct 18:47 | Version 3.1.2 deployed (fixes that config). This is what runs **now** |

## 3. The same incident, asked two ways

| Test | How the incident was described | What the agent did | Result |
| --- | --- | --- | --- |
| H1 | "...failing **around noon**... Investigate." | Found the cache, saw the 12:35 rollback, suggested a code fix, no rollback | Pass |
| H2 | "...kept restarting **late in the morning**... What happened?" | Found the cache, then asked to **roll back to 3.0.4** | Fail (stopped at approval) |

## 4. Why H2 went wrong (most likely)

Two of my own instructions pulled against each other:

1. "Stay inside the time of day the reporter gives you. **Morning means 06:00 to 12:00.**"
2. "Only roll back if the problem is **still happening**."

"Late in the morning" means the agent looked only until 12:00. At 12:00 the errors were still going,
and the 12:35 rollback was outside its window. So from where it stood, the problem looked ongoing,
and it followed rule 2.

Analogy: a security guard is told to watch only the morning CCTV. They see a thief walk in at
11:45 and raise the alarm. The thief was caught at 12:35, but that's on the afternoon tape they
were told not to watch.

**Not proven:** that test run did not save its step-by-step trace, so I can't see which time window
it actually searched. The clue that supports it: H1's answer mentions the 12:35 rollback, H2's
does not.

## 5. Why the miss was worse than it looked

The agent asked to go back to 3.0.4. But by now the service runs 3.1.2. Going straight back to 3.0.4
would also have **undone the two releases from 15 Oct**, including a config fix. So it wasn't just
a pointless rollback; approving it would have caused harm.

## 6. What I changed (commit b2adfd6)

**A rollback can only go one step back from the version running now.**

Like Ctrl+Z: it undoes the last change, not ten changes at once.

- Asking for the version that's already running: refused ("nothing to roll back").
- Asking to go further back than one step: refused ("would undo every change since").
- The check runs **before** a human is asked, so nobody gets a pointless approval request. The
  refusal goes back to the agent so it can rethink. It runs again just before the rollback
  happens, in case something was deployed meanwhile.
- The genuine case (welcome emails broken by 1.9.0, still happening) still works: 1.9.0 back to
  1.8.0 is exactly one step.

**The eval report now saves every step** (each tool call, its time window, and the start of its
result), so the next miss can be traced instead of guessed.

**Tests:** 33 in total (21 unit, 12 integration), all passing. One integration test sends H2's exact
rollback request and checks it is refused without asking anyone.

## 7. What I did NOT change, and why

- **The instructions (prompt).** Changing them because of a held-out test would be "studying the
  exam paper", and the held-out set would stop being a fair test.
- **The published results.** The 5/6 (83%) is still from the original run. If H2 passes in a future
  run, it is because the **code refused** the rollback, not because the agent reasoned better. The
  README says this.

## 8. Trade-off and what's still missing

- The agent can no longer propose rolling back two or more versions. A human does that by hand.
- There is no tool that answers "what is happening right now?" Every tool looks at a time window
  the agent picks. A tool showing each service's current version and error rate would fix the root
  of this miss.

## 9. If someone asks about it

**"What went wrong on the held-out set?"**
It found the right cause, a memory leak from a new cache, but proposed rolling back a release that
had already been rolled back. Most likely it stayed inside the "morning" window the report gave,
and the rollback happened at 12:35.

**"How did you fix it?"**
Not by tuning the prompt. I added a rule in code: a rollback can only go one step back from what's
running now, checked before a human is asked. The approval gate is still there as the last line of
defence.

**"Why not just fix the prompt?"**
Because that tunes the agent to the test it is meant to be measured on. A code rule is general: it
protects against any version of this mistake, from any model.
