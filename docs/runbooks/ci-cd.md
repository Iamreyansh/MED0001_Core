# CI / CD runbook

Pipelines live under `.github/workflows/` and call the same Makefile targets as local work.

## Pipelines

```text
PR → main
  quality-gates
    java          make check (+ OWASP advisory)
    gitleaks
    scripts       make scripts-syntax
    terraform     make tf-fmt-check + tf-validate
                  (+ tf-plan staging/prod when infra/deploy paths change)

main push
  deploy-main
    guard         require AWS_DEPLOY_ROLE_ARN
    build         make check + make package → lambda-zips artifact
    staging       make deploy ENV=staging → smoke-remote
    prod          make deploy ENV=prod → smoke-remote
    release       GitHub Release + attach api.zip / worker.zip
```

| Workflow | Trigger |
|----------|---------|
| `quality-gates.yml` | PR to `main`, or `workflow_dispatch` |
| `deploy-main.yml` | Push to `main` |
| `terraform-force-unlock.yml` | Manual unlock |

Repo variable: `AWS_DEPLOY_ROLE_ARN` (OIDC). Environments: `staging`, `prod`. See [github-setup.md](github-setup.md).

## Makefile entrypoints used by CI

| Target | Role |
|--------|------|
| `make check` | Spotless, tests, JaCoCo 100%, SpotBugs, ArchUnit |
| `make dependency-check` | OWASP (advisory on PRs) |
| `make scripts-syntax` | `bash -n` on `scripts/**/*.sh` |
| `make package` | `infra/lambda/{api,worker}.zip` |
| `make tf-fmt-check` / `tf-validate` | Cheap always-on TF gates |
| `make tf-plan ENV=staging\|prod` | Remote-state plan |
| `make deploy ENV=staging\|prod` | Apply + publish Lambdas to alias `live` |
| `make smoke-remote HEALTH_URL=…` | HTTP 200 + `success:true` + `status:UP` |
| `make tf-unlock ENV=… LOCK_ID=…` | Force-unlock stuck state |

## Deploy semantics (important)

API Gateway invokes Lambda alias **`live`**, not `$LATEST`.

`scripts/deploy-stack.sh`:

1. Ensures artifacts bucket (targeted apply)
2. Uploads zips to S3
3. Full `terraform apply`
4. `scripts/ci/publish-lambda.sh` — `update-function-code --publish` + wait + `update-alias live`

Terraform often no-ops code when the S3 key is unchanged (no `source_code_hash` yet); the publish/alias step is what moves traffic.

## Failure playbook

| Symptom | Action |
|---------|--------|
| Terraform lock error on plan/apply | Copy lock UUID → [terraform-force-unlock.md](terraform-force-unlock.md) / `/tf-unlock`. Confirm no intentional apply is running first. |
| Staging deploy or smoke fails | Fix forward on `main` or revert. Do not skip staging on retry. |
| Prod fails after staging OK | Treat as incident (EPIC-020 / ops). Re-run only after root cause; never bypass staging. |
| Smoke HTTP 200 but body wrong | Expect `{"success":true,"data":{"status":"UP"}}`. Check alias `live` version and SnapStart publish. |
| `AWS_DEPLOY_ROLE_ARN` empty | Admin sets repo variable from staging `deploy_role_arn` output. |
| PR terraform job fails “role empty” | Infra/deploy paths changed; role must be set before plan can run. |

## Composite actions

| Path | Purpose |
|------|---------|
| `.github/actions/setup-java` | Temurin 21 + `gradle/actions/setup-gradle` User Home cache |
| `.github/actions/setup-terraform-aws` | Terraform 1.10.5 + OIDC assume role (`ap-south-1`) |

## Gradle cache (CI)

- Restored/saved via `gradle/actions/setup-gradle` (dependency jars + Gradle build cache under `~/.gradle`).
- Cache **key** hashes Gradle build files, `gradle/libs.versions.toml`, and the wrapper properties.
- **Dep update?** Change a version in the catalog / `*.gradle*` → new key → cache miss → download new artifacts. Unchanged deps still hit cache.
- PRs are **read-only** on the cache; `main` / deploy jobs **write** so one bad PR cannot poison the shared cache.
- `gradle.properties` enables `org.gradle.caching` + `org.gradle.parallel`. CI sets `CI=true` so Makefile skips `--no-daemon`.
