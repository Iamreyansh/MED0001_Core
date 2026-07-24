# Review story

Usage: `/review-story EPIC-00X STORY-00Y` (or current PR / changed files)

Run a structured pre-merge review for one story.

## Order

1. **Contract** — agent `api-contract-guardian`  
   Controllers vs story API Endpoints (paths, auth, envelopes, error codes, pagination).
2. **Security** — agent `security-reviewer` (+ skill `security-review-medmate`)  
   JWT/RBAC, secrets, PII/Rx logs, webhooks, uploads.
3. **Reliability** — agent `reliability-sre` (when the story touches events, payments, queues, uploads, schedules)  
   Outbox, idempotency, DLQ/alarms, async boundaries.
4. **Tests / coverage** — agent `test-engineer`  
   AC mapping + `make coverage` / `make check`.

## Report template (combine into one reply)

```
## Story review: EPIC-XXX STORY-YYY
Overall: PASS | FAIL

### Contract
…

### Security
…

### Reliability
… (or N/A)

### Tests
…

### Required fixes before merge
- [ ] …
```

## Done when

No **BLOCK** findings remain across contract, security, reliability (if applicable), and tests.
