# Runbook: Terraform force unlock

1. Open failed GitHub Action log; copy `Lock Info` ID.
2. Actions → **Terraform Force Unlock** → Run workflow.
3. Inputs: `environment` (`staging`|`prod`), `lock_id`.
4. Confirm no other apply is running, then re-run deploy/plan.
