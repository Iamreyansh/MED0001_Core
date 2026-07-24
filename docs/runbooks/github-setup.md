# GitHub setup (admin required)

Current automation account may only have **write** on `Iamreyansh/MED0001_Core`. A repo **admin** should:

1. Create environments `staging` and `prod` (Settings → Environments). Protect `prod` with required reviewers if desired.
2. After first staging Terraform apply, set repository variable `AWS_DEPLOY_ROLE_ARN` to the `deploy_role_arn` output (or the CLI-created role).
3. Protect `main`:
   - Require PR
   - Required checks: `java`, `gitleaks`, `terraform`
   - Dismiss stale reviews
   - No force push

Workflows are already in `.github/workflows/`.
