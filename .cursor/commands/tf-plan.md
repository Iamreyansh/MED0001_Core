# Terraform plan

Usage: `/tf-plan staging` or `/tf-plan prod`

Plan against the remote S3 backend. Prefer CI; use local plan only with valid AWS credentials.

## Backend

`s3://terraform-locks-105927215604/MED0001/{env}/terraform.tfstate`  
(`use_lockfile = true`, region `ap-south-1`)

## Preferred: CI

- Push/PR infra changes → `quality-gates` plans when Terraform paths change.
- Merge to `main` → `deploy-main` applies staging then prod.

## Local plan (optional)

```bash
make tf-fmt
make tf-validate          # no backend; cleans local .terraform after
make tf-plan ENV=staging  # or ENV=prod — needs AWS creds + remote state
```

Or follow skill `terraform-change` / agent `infra-terraform`.

## Hard rules

- Never keep or commit local state, locks, `.terraform/`, or `.terraform.lock.hcl`.
- Auth via OIDC / short-lived creds — no long-lived access keys in the repo.
- Stuck lock → `/tf-unlock` (skill `terraform-force-unlock`).

## Done when

Plan output reviewed; blast radius noted in the PR; no state artifacts left in the working tree.
