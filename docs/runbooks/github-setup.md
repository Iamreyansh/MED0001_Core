# GitHub + AWS setup (CLI)

One-shot bootstrap (OIDC provider, deploy role trust, repo variable, environments):

```bash
# AWS creds for account 105927215604 + gh with repo admin (for environments)
./scripts/bootstrap-github-aws.sh
```

What it configures:

| Item | Value |
|------|--------|
| OIDC provider | `token.actions.githubusercontent.com` |
| IAM role | `arn:aws:iam::105927215604:role/med0001-gha-deploy` |
| Repo variable | `AWS_DEPLOY_ROLE_ARN` |
| Environments | `staging`, `production` (needs **repo admin** on `gh`; else first workflow run creates them) |
| CI S3 bucket | `med0001-gha-ci-105927215604` (Gradle cache, boot jars, reports, tf plans — **not** GitHub Actions storage) |

## CI S3 storage (no GitHub Actions cache/artifacts)

All durable CI blobs go to the shared bucket owned by the **staging** Terraform stack (account `105927215604`). Quota for Actions caches/artifacts is on the **repo owner** account; this design keeps that usage at zero.

| Prefix | Contents | Lifecycle |
|--------|----------|-----------|
| `gradle-cache/` | tar of `~/.gradle/{caches,wrapper}` | 14 days |
| `artifacts/<sha>/` | boot jars between `deploy-main` jobs | 3 days |
| `reports/<run_id>/` | JaCoCo/SpotBugs/test reports on failure | 7 days |
| `tfplans/<run_id>/` | terraform plan text from quality-gates | 7 days |

Bootstrap: `deploy-main` runs a targeted staging apply (`aws_s3_bucket.gha_ci` + lifecycle/encryption/PAB + `gha_deploy` IAM) before uploading jars. Full staging apply still runs in `deploy-staging`. Break-glass locally:

```bash
make tf-init ENV=staging
terraform -chdir=infra/terraform/stacks/staging apply -auto-approve \
  -target=aws_s3_bucket.gha_ci \
  -target=aws_s3_bucket_public_access_block.gha_ci \
  -target=aws_s3_bucket_server_side_encryption_configuration.gha_ci \
  -target=aws_s3_bucket_lifecycle_configuration.gha_ci \
  -target=aws_iam_role_policy.gha_deploy
```

## OIDC subject claim (important)

GitHub may emit either:

- classic: `repo:Iamreyansh/MED0001_Core:environment:staging`
- with ids: `repo:Iamreyansh@43453546/MED0001_Core@1309166125:environment:staging`

The bootstrap script trusts **both** via `StringLike`. If assume-role fails with `Not authorized to perform sts:AssumeRoleWithWebIdentity`, re-run the script (it refreshes trust from live repo ids).

## Branch protection (admin UI or API)

Protect `main`:

- Require PR
- Required checks: `java`, `gitleaks`, `scripts`, `terraform`
- Dismiss stale reviews
- No force push

## Verify

```bash
gh variable list --repo Iamreyansh/MED0001_Core
gh api repos/Iamreyansh/MED0001_Core/environments --jq '.environments[].name'
aws iam get-role --role-name med0001-gha-deploy \
  --query 'Role.AssumeRolePolicyDocument' --output json
```

Pipeline details: [ci-cd.md](ci-cd.md).
