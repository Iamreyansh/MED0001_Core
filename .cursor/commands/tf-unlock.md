# Terraform unlock

Usage: `/tf-unlock`

Force-unlock a stuck Terraform state lock via GitHub `workflow_dispatch`. Follow skill `terraform-force-unlock`.

## Steps

1. From the failed CI log, copy **Lock Info** / `ID:` (UUID).
2. Confirm no other plan/apply is intentionally running for that environment.
3. Dispatch workflow `.github/workflows/terraform-force-unlock.yml`:

```bash
gh workflow run terraform-force-unlock.yml \
  -f environment=staging \
  -f lock_id='<LOCK_UUID>'
```

Use `environment=prod` when the lock is on prod.

4. Wait for the workflow to succeed, then re-run the failed plan/apply job.

## Danger

- Unlocking while another apply is live can corrupt state.
- Do **not** delete lock objects in S3 by hand unless an admin explicitly instructs it.

## Done when

Workflow green and the original plan/apply can proceed.
