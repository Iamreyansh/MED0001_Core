# AGENTS

Namma MedMate Core (`MED0001_Core`) — Java 21 / Spring Boot modular monolith.

## Contracts

- Read `docs/requirements/INDEX.md` and `docs/architecture/MODULE_MAP.md` before coding.
- Ponytail rule (`.cursor/rules/ponytail.mdc`) + **100% JaCoCo** are both mandatory.
- Never implement outside the story scope you were given.
- Never commit Terraform state, locks, or `.terraform/` — only `s3://terraform-locks-105927215604/MED0001/`.

## Layout

- `apps/api`, `apps/worker` — composition roots
- `platform/*` — technical shared (no business rules)
- `domains/*` — epic shells / business modules
- `db/migration` — sole schema history
- `bruno/` — git-tracked Bruno API collection (keep in sync with every endpoint change)
- `infra/terraform` — modules + stacks
- Day-to-day: `Makefile` + Podman (`make help`)

## When to use what (`.cursor/`)

| Need | Use |
|------|-----|
| Pick + implement the next pending STORY | Command `/next-story` → tracker `docs/requirements/AGENT-REQUIREMENT-IMPLEMENTATION.md` |
| Implement one STORY | Command `/implement-story` → skill `implement-story` → agent `epic-implementer` |
| Pre-merge review | Command `/review-story` → agents `api-contract-guardian`, `security-reviewer`, `test-engineer` (+ `reliability-sre` if async/payments) |
| Local smoke / gates | Command `/bootstrap-verify` (`make bootstrap-verify`, then `make start-bg` / `make health`) |
| Schema change | Skill `add-flyway-migration` |
| Terraform edit | Skill `terraform-change` → agent `infra-terraform` → `/tf-plan` |
| Stuck state lock | `/tf-unlock` → skill `terraform-force-unlock` |
| Lambda package / SnapStart | Skill `lambda-snapstart` |
| Security checklist | Skill `security-review-medmate` → agent `security-reviewer` |
| Ship to prod | Command `/ship-main` |
| HTTP endpoint create/update | Rule `bruno-api.mdc` → update matching `.bru` under `bruno/` (scripts + tests) |

## Commands

See `.cursor/commands/` (`bootstrap-verify`, `next-story`, `implement-story`, `review-story`, `tf-plan`, `tf-unlock`, `ship-main`).

Story implementation status lives only in `docs/requirements/AGENT-REQUIREMENT-IMPLEMENTATION.md` (rule `story-tracker.mdc`).

## Agents

See `.cursor/agents/` (`epic-implementer`, `api-contract-guardian`, `test-engineer`, `security-reviewer`, `infra-terraform`, `reliability-sre`).

## Skills

See `.cursor/skills/` (`implement-story`, `add-flyway-migration`, `terraform-change`, `terraform-force-unlock`, `lambda-snapstart`, `security-review-medmate`).
