# Runbook: Terraform force unlock

1. Open failed GitHub Action log; copy `Lock Info` ID (UUID).
2. Confirm no intentional apply is still running for that environment.
3. Actions → **terraform-force-unlock** → Run workflow (needs `AWS_DEPLOY_ROLE_ARN`).
4. Inputs: `environment` (`staging`|`prod`), `lock_id`.
5. After success, re-run the failed plan/apply workflow.

CLI equivalent:

```bash
gh workflow run terraform-force-unlock.yml \
  -f environment=staging \
  -f lock_id='<LOCK_UUID>'
```

Do not delete S3 lock objects by hand unless an admin explicitly instructs it. See also [ci-cd.md](ci-cd.md).
