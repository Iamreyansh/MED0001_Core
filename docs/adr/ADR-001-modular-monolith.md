# ADR-001: Modular monolith on Lambda

## Decision

Ship a Gradle multi-module modular monolith (`apps/api` + `apps/worker`) with epic-aligned `domains/*` and shared `platform/*`, deployed to AWS Lambda.

## Consequences

- Fast agent navigation via MODULE_MAP.
- Single Flyway timeline.
- Later extract workers/domains without rewriting API contracts.
