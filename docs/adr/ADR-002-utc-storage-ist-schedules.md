# ADR-002: UTC storage, IST schedules

## Decision

Persist all timestamps in UTC (ISO-8601). Run business schedules (settlements, batches) in `Asia/Kolkata` via EventBridge.

## Consequences

- API responses remain UTC per INDEX.
- Cron expressions must not assume UTC Mondays for India finance jobs.
