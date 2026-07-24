---
name: terraform-change
description: Safe Terraform edits against S3 backend for MED0001; never local state. Use when changing infra/terraform.
---

# Terraform change

## Layout

| Path | Role |
|------|------|
| `infra/terraform/modules/*` | Reusable resources (prefer edits here) |
| `infra/terraform/stacks/{staging,prod}` | Thin wiring / env inputs only |

## Steps

1. Edit the smallest module surface that satisfies the need; keep stacks thin.
2. Format/validate locally:

```bash
make tf-fmt
make tf-validate
```

3. Plan via CI (preferred) or `make tf-plan ENV=staging|prod` with short-lived AWS creds.
4. Backend is remote S3 only: `s3://terraform-locks-105927215604/MED0001/{env}/`.
5. Never commit lock/state/provider lockfiles / `.terraform/`.

## PR blast-radius note (required)

```markdown
### Terraform blast radius
- Env: staging | prod | both
- Modules touched: …
- Resources created/changed/destroyed: …
- Rollback: revert PR / prior module version; state unlock via /tf-unlock if stuck
```

## Done when

Plan reviewed, no state artifacts in git, blast radius documented.
