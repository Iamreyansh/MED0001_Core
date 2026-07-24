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
| Environments | `staging`, `prod` (needs **repo admin** on `gh`) |

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
