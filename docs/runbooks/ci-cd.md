# CI / CD runbook

Pipelines live under `.github/workflows/` and call the same Makefile targets as local work.

## Pipelines

```text
PR → main
  quality-gates
    java          make check
    gitleaks
    scripts       make scripts-syntax
    terraform     make tf-fmt-check + tf-validate
                  (+ tf-plan staging + prod when infra paths change)

main push
  deploy-main
    guard         require AWS_DEPLOY_ROLE_ARN
    build         make check + make jar → boot-jars artifact
    staging       push SHA images → tf apply → ECS roll → smoke
    tag-release   annotated semver tag vMAJOR.MINOR.PATCH (patch bump; does not deploy prod)

manual
  deploy-prod     workflow_dispatch with tag=vX.Y.Z
    promote       crane-copy staging digests → prod ECR (:tag + :prod)
    apply         TF_VAR_image_tag=<tag> → tf apply prod
    roll          ECS services-stable
    smoke         https://core.api.nammamedmate.com/api/v1/health
```

| Workflow | Trigger |
|----------|---------|
| `quality-gates.yml` | PR to `main`, or `workflow_dispatch` |
| `deploy-main.yml` | Push to `main` → staging → semver tag |
| `deploy-prod.yml` | Manual `workflow_dispatch` only (`tag` = `vX.Y.Z`) |
| `terraform-force-unlock.yml` | Manual unlock |

Version tags: first successful staging is `v0.1.0`, then each new SHA bumps **patch** (`v0.1.1`, …). Re-running the same SHA is idempotent (no new tag).

Promote to prod:

```bash
gh workflow run deploy-prod.yml -f tag=v0.1.0
```

Repo variable: `AWS_DEPLOY_ROLE_ARN` (OIDC). Environments: `staging`, `production`.

## State

| Env | Backend key |
|-----|-------------|
| staging | `s3://terraform-locks-105927215604/MED0001/staging/terraform.tfstate` |
| prod | `s3://terraform-locks-105927215604/MED0001/prod/terraform.tfstate` |

## Makefile entrypoints used by CI

| Target | Role |
|--------|------|
| `make check` | Spotless, tests, JaCoCo 100%, SpotBugs, ArchUnit |
| `make jar` | Boot jars for Docker |
| `make scripts-syntax` | `bash -n` on `scripts/*.sh` |
| `make tf-fmt-check` / `tf-validate` | Cheap always-on TF gates (staging + prod) |
| `make tf-plan ENV=staging\|prod` | Remote-state plan |
| `make tf-apply ENV=staging\|prod` | Apply (break-glass locally) |
| `make deploy-ecs ENV=…` | Force rolling deploy + `services-stable` |
| `make smoke-remote HEALTH_URL=…` | HTTP 200 + `success:true` + `status:UP` |

## Deploy semantics

- **Staging** builds and pushes ARM64 images tagged with the full `GITHUB_SHA`.
- **Prod** never rebuilds: `crane copy` promotes the exact staging digests to `med0001-prod-{api,worker}:vX.Y.Z`.
- API ECS: `desired_count=1`, max 200% / min 100% healthy, `health_check_grace_period_seconds=120`.

## Failure playbook

| Symptom | Action |
|---------|--------|
| Terraform lock error | `/tf-unlock` with `ENV` + `LOCK_ID` |
| Staging smoke fails | No release tag created; fix forward on `main` |
| Prod fails after tag | Incident; re-run `deploy-prod` on the same tag after fix, or ship a new staging SHA |
| Smoke body wrong | Expect `{"success":true,"data":{"status":"UP"}}` |
| `AWS_DEPLOY_ROLE_ARN` empty | `gh variable set` / `scripts/bootstrap-github-aws.sh` |

## Composite actions

| Path | Purpose |
|------|---------|
| `.github/actions/setup-java` | Temurin 21 + Gradle cache |
| `.github/actions/setup-terraform-aws` | Terraform 1.10.5 + OIDC (`ap-south-1`) |
