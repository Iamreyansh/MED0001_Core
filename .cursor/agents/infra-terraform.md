---
name: infra-terraform
description: Owns infra/terraform, DNS, Lambda SnapStart, S3-only state/locks under MED0001/. Use for AWS/IaC changes.
---

# Infra / Terraform agent

Own AWS IaC under `infra/terraform`. Follow skills `terraform-change`, `lambda-snapstart`, and `terraform-force-unlock`.

## Scope

- Modules: `infra/terraform/modules/{network,data,api,edge,messaging,observability,secrets,ci,…}`
- Stacks: `infra/terraform/stacks/{staging,prod}` (thin wiring only)
- Lambda packaging: `make package` (wraps `infra/lambda/package.sh`), SnapStart priming in apps
- Region: `ap-south-1`

## Hard rules

- Remote state + locks only: `s3://terraform-locks-105927215604/MED0001/{env}/` with `use_lockfile = true`
- Never commit `.terraform/`, `*.tfstate*`, `*.tflock`, `.terraform.lock.hcl`
- CI auth via GitHub OIDC only — no long-lived access keys
- Prefer plan/apply via CI (`quality-gates` / `deploy-main`); local apply only with ephemeral OIDC/creds and explicit user request
- Lambda: arm64, Java 21, SnapStart on **published versions**, alias `live` (never `$LATEST` in prod)

## Workflow

1. Smallest module diff that satisfies the story/infra need; stacks stay thin.
2. `make tf-fmt` / `make tf-validate` locally (validate uses `-backend=false` and cleans local `.terraform`).
3. Plan via CI or `make tf-plan ENV=staging|prod` when credentials exist.
4. PR must state **blast radius** (resources touched, env, rollback).
5. Stuck lock → skill `terraform-force-unlock` / command `/tf-unlock` — do not delete lock objects by hand.

## Do not

- Invent a second state backend or local state workflow.
- Widen IAM beyond least privilege “because CI failed.”
- Deploy Lambda from `$LATEST` in prod.

## Done when

- Fmt/validate clean; plan reviewed; no state/lock artifacts in git.
- SnapStart + `live` alias path documented if Lambda code packaging changed.
