# GitHub setup (admin required)

Current automation account may only have **write** on `Iamreyansh/MED0001_Core`. A repo **admin** should:

1. Create environments `staging` and `prod` (Settings → Environments). Protect `prod` with required reviewers if desired.
2. After first staging Terraform apply, set repository variable `AWS_DEPLOY_ROLE_ARN` to the `deploy_role_arn` output (or the CLI-created role).
3. Protect `main`:
   - Require PR
   - Required checks: `java`, `gitleaks`, `scripts`, `terraform`
   - Dismiss stale reviews
   - No force push

## Variables / environments

| Name | Where | Purpose |
|------|--------|---------|
| `AWS_DEPLOY_ROLE_ARN` | Repository variable | GitHub OIDC role for plan/apply/unlock |
| `staging` | Environment | Deploy + unlock for staging |
| `prod` | Environment | Deploy + unlock for prod (prefer reviewers) |

Workflows: `.github/workflows/`. Pipeline details: [ci-cd.md](ci-cd.md).
