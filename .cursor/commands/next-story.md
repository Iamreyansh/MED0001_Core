# Next story

Usage: `/next-story` — or `/next-story EPIC-00X STORY-00Y` to force a specific story.

Pick the next pending story, plan it, implement it, and mark it done in the tracker.

## Steps

1. **Pick.** Open `docs/requirements/AGENT-REQUIREMENT-IMPLEMENTATION.md` (the tracker). Take the first `pending` row (top-to-bottom = phase → epic → story order) whose story-file Dependencies are all `done` in the tracker. If args were given, use that story instead. If a row's dependencies aren't met, set it `blocked` with the reason in Notes and move to the next row.
2. **Claim.** Set the chosen row's status to `in_progress`.
3. **Plan.** Read the story file fully and `docs/architecture/MODULE_MAP.md`. Before writing code, state a short plan: target `domains/{name}` module, endpoints, entities/migrations, events (outbox), and which tests cover each acceptance criterion.
4. **Implement.** Follow `/implement-story` (skill `implement-story`, agent `epic-implementer` if delegating). Schema changes only via skill `add-flyway-migration`.
5. **Verify.**

```bash
make format
make check
```

6. **Mark done.** Only after `make check` is green: set the row to `done`, Completed = today (`YYYY-MM-DD`), Notes = `domains/{name}` + any deviations. Update the Progress table counts.
7. Hand off to `/review-story` before merge.

## Guardrails

- One story per run. Never batch rows to `done`.
- Never mark `done` with a red `make check` — leave it `in_progress` and say why.
- Status lives only in the tracker; never edit STORY/EPIC docs to record status.
- All `/implement-story` guardrails apply (story scope only, no domain→domain deps, JaCoCo 100%).
