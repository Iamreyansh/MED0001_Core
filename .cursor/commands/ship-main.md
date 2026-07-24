# Ship main

Promote a green PR through `main` to staging → prod.

## Preconditions

1. `/review-story` (or equivalent) has no BLOCK findings.
2. PR checks green — `java`, `gitleaks`, `scripts`, `terraform` (`quality-gates`).
3. No open Terraform lock on staging/prod.
4. Repo variable `AWS_DEPLOY_ROLE_ARN` is set.

## Steps

1. Merge the PR to `main` (no force-push to `main`).
2. Workflow `deploy-main` runs:
   - Guard: require `AWS_DEPLOY_ROLE_ARN`
   - `make check` + `make package`
   - `make deploy ENV=staging` (terraform apply + publish Lambdas to alias `live`) + `make smoke-remote`
   - `make deploy ENV=prod` + `make smoke-remote`
   - GitHub Release with `api.zip` / `worker.zip` attached
3. Verify production health:

```bash
make smoke-remote HEALTH_URL=https://core.api.nammamedmate.com/api/v1/health
```

Expect HTTP 200 and `{"success":true,"data":{"status":"UP"}}`.

## If deploy fails

- Read the failed job log; do not re-run apply blindly on lock errors → `/tf-unlock` first.
- Staging failure: fix forward on `main` or revert; do not “hotfix” by editing state.
- Prod failure after staging OK: treat as incident; follow EPIC-020 / ops process — do not skip staging on retry.
- See [`docs/runbooks/ci-cd.md`](../../docs/runbooks/ci-cd.md).

## Done when

Prod smoke is UP (envelope + status) and the GitHub Release exists for the merge commit.
