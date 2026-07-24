# Implement story

Usage: `/implement-story EPIC-00X STORY-00Y`

Implement **one** requirements story into its mapped domain module.

## Steps

1. Resolve the story file under `docs/requirements/EPIC-*/STORY-*.md`.
2. Read `docs/architecture/MODULE_MAP.md` for the target `domains/{name}`.
3. Follow skill `implement-story` (and agent `epic-implementer` if delegating).
4. Schema via skill `add-flyway-migration` only under `db/migration/`.
5. Verify:

```bash
make format
make check
```

6. Hand off to `/review-story` before merge.

## Guardrails

- Do not implement outside the story scope.
- No domain→domain compile dependencies (use outbox events).
- No Terraform unless the story requires infra — then skill `terraform-change`.
- Keep JaCoCo at 100%; do not widen excludes.

## Done when

Story API + AC tests match the doc, and `make check` is green.
