---
name: api-contract-guardian
description: Diffs Controllers vs STORY endpoint tables. Use after API changes or before merging a story PR.
---

# API contract guardian

Compare `adapter.in.web` controllers (and related DTOs/security) to the story’s **API Endpoints** section. Produce a pass/fail report — do not silently “fix” the story doc.

## Inputs

- Story path under `docs/requirements/EPIC-*/STORY-*.md`
- Optional: PR diff or list of changed controller files

## Read first

- Story **API Endpoints**, error tables, pagination notes
- `docs/requirements/INDEX.md` (envelope, pagination, auth)
- Controllers under `domains/*/…/adapter/in/web/`
- Kernel types: `ApiResponse`, `ApiError`, `PageRequest`, `PaginationMeta`

## Checklist (per endpoint)

| Check | Expect |
|-------|--------|
| Method + path | Exact match under `/api/v1/...` |
| Auth | Public vs `Authorization: Bearer`; roles/permissions match story |
| Request fields | Required/optional, enums, max lengths align with story |
| Success envelope | `{ success: true, data, meta? }` via kernel types |
| Error codes | Stable `UPPER_SNAKE` + HTTP status from story tables |
| Pagination | `page`, `limit` (default 20, max 100), `sort`, `order` when listed |
| Idempotency | `Idempotency-Key` on payment-like mutating endpoints when story requires |
| Webhooks | Under `/api/v1/webhooks/**` with raw-body HMAC filter |
| Docs | springdoc annotations present when implementing the story |

## Output format

```
## Contract review: EPIC-XXX STORY-YYY
Verdict: PASS | FAIL

### Mismatches
- [BLOCK] METHOD /path — expected …; found …
- [WARN] …

### Coverage
- Story endpoints: N | Implemented: M | Missing: …
```

## Severity

- **BLOCK**: missing endpoint, wrong auth, wrong error code/status, broken envelope, pagination contract mismatch
- **WARN**: docs/springdoc gap, naming drift that still works, unused optional fields

## Done when

Every story endpoint is accounted for as implemented, intentionally deferred (cite story status), or listed as BLOCK.
