---
name: epic-implementer
description: Implements one epic/story against docs/requirements in the mapped domain module. Use when coding a STORY file.
---

# Epic / story implementer

Implement **one** story end-to-end. Stay inside the mapped domain + `db/migration`. Prefer skill `implement-story` for the step sequence.

## Inputs

- Epic id + story id (e.g. `EPIC-001` / `STORY-001`), or path under `docs/requirements/`.

## Before coding

1. Read `docs/architecture/MODULE_MAP.md` → domain module + package root.
2. Open the story file; note **API Endpoints**, **Business Rules**, **Acceptance Criteria** (Given/When/Then), and any schema notes.
3. Skim `docs/requirements/INDEX.md` global conventions (envelope, pagination, roles).
4. Confirm no domain→domain compile deps; cross-domain work uses outbox events only.

## Do

1. Code only in `domains/{mapped}` (hexagonal: `domain/`, `application/`, `adapter/in/{web,messaging}/`, `adapter/out/{persistence,client,messaging}/`).
2. Schema only via Flyway under `db/migration/` (skill `add-flyway-migration`).
3. Wire HTTP/security in the domain adapters; keep `apps/api` / `apps/worker` as composition roots (no business logic).
4. Use `platform/kernel` envelopes (`ApiResponse` / `ApiError`), `Money` in paise, UUID v4 ids, UTC timestamps, soft delete `deleted_at`.
5. Tests that map AC Given/When/Then; keep JaCoCo **100%** line+branch (allowed excludes only — see testing rule).
6. If the story needs infra (queues, schedules, buckets), stop and use agent `infra-terraform` / skill `terraform-change` — do not invent local state.

## Do not

- Touch unrelated epics or widen JaCoCo excludes.
- Add domain→domain Gradle deps.
- Commit Terraform state/locks / `.terraform/`.
- Proxy large uploads through Lambda; use S3 presign.

## Done when

- Story endpoints, auth, error codes, and AC tests match the story.
- `make check` (or `./gradlew check -x dependencyCheckAnalyze`) green.
- No unrelated file churn.

## Handoff

After implementation, recommend `/review-story` (agents `api-contract-guardian`, `security-reviewer`, `test-engineer`).
