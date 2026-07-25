---
name: implement-story
description: Implement one Namma MedMate story from docs/requirements into the correct domain module end-to-end. Use when coding a STORY file or running /implement-story.
---

# Implement story

## Steps

1. Read `docs/architecture/MODULE_MAP.md` and the story under `docs/requirements/EPIC-XXX/...`.
2. Implement **only** that story in `domains/{name}` using hexagonal packages:
   - `domain/`, `application/`
   - `adapter/in/{web,messaging}/`
   - `adapter/out/{persistence,client,messaging}/`
3. Add Flyway SQL under `db/migration/` via skill `add-flyway-migration`.
4. Wire controllers/security as specified; keep `apps/*` as composition roots (no business rules).
5. Sync `bruno/` for every new/changed/removed HTTP endpoint (rule `bruno-api.mdc`): path-mirrored `.bru`, pre/post scripts that chain env vars, envelope `tests`.
6. Map each Given/When/Then (or AC-00N) to tests; keep JaCoCo at 100%.
7. Do not change unrelated epics. Terraform only if the story requires infra → skill `terraform-change`.

## Conventions (INDEX)

- Base path `/api/v1`; envelopes via `platform/kernel` (`ApiResponse` / `ApiError`)
- UUID v4 ids; ISO-8601 UTC; soft delete `deleted_at`; money in paise (`Money`)
- No domain→domain compile deps — publish via transactional outbox

## Verify

```bash
make format
make check
```

## Done when

- Endpoints, auth, error codes, and AC tests match the story
- `bruno/` requests match the story contract (scripts automate the happy path)
- `make check` green
- Ready for `/review-story` (contract + security + tests)
