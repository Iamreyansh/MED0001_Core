---
name: add-flyway-migration
description: Add versioned Flyway SQL under db/migration with rollback notes. Use when schema changes.
---

# Add Flyway migration

## Rules

- Only write SQL under `db/migration/` (never per-domain Flyway trees).
- PostgreSQL-compatible; timestamps `TIMESTAMPTZ`; money as `BIGINT` paise; ids `UUID`.
- Soft delete columns: `deleted_at TIMESTAMPTZ NULL`.

## Naming

Prefer sequential after the latest `V00x` / `V0xx` file already in the tree:

```text
V002__epic001_story001_otp_sessions.sql
```

If the team is using timestamp versions instead, use:

```text
V{YYYYMMDDHHMM}__epic{NNN}_story{NNN}_{slug}.sql
```

Do **not** mix both schemes in one PR without an explicit reason. Match the newest existing migration’s style.

## Header template

```sql
-- EPIC-001 / STORY-001: otp_sessions
-- Rollback: DROP TABLE IF EXISTS otp_sessions;
-- Notes: bcrypt hash only; no plaintext OTP

CREATE TABLE ...
```

## Done when

- File is the next version after HEAD’s latest migration
- Rollback notes are in the header
- App starts with `make start-bg` / Flyway applies cleanly against Postgres (`profile=podman`)
