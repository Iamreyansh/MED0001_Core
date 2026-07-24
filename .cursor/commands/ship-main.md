# Ship main

Promote a green PR through `main` to staging → prod.

## Preconditions

1. `/review-story` (or equivalent) has no BLOCK findings.
2. PR checks green — especially `quality-gates`.
3. No open Terraform lock on staging/prod.

## Steps

1. Merge the PR to `main` (no force-push to `main`).
2. Workflow `deploy-main` runs:
   - `./gradlew check -x dependencyCheckAnalyze`
   - Package api + worker Lambdas (`infra/lambda/package.sh`)
   - Terraform apply **staging**, smoke health
   - Terraform apply **prod**, smoke health
   - Create GitHub Release
3. Verify production health:

```bash
curl -sS https://core.api.nammamedmate.com/api/v1/health
```

Expect HTTP 200 and `{"success":true,"data":{"status":"UP"}}` (or equivalent UP payload).

## If deploy fails

- Read the failed job log; do not re-run apply blindly on lock errors → `/tf-unlock` first.
- Staging failure: fix forward on `main` or revert; do not “hotfix” by editing state.
- Prod failure after staging OK: treat as incident; follow EPIC-020 / ops process — do not skip staging on retry.

## Done when

Prod health is UP and the GitHub Release exists for the merge commit.
