---
name: terraform-force-unlock
description: Unlock stuck Terraform state lock via GitHub workflow_dispatch. Use when CI plan/apply fails on state lock.
---

# Terraform force unlock

## Steps

1. Open the failed CI log; copy **Lock Info** / `ID:` (UUID).
2. Confirm no intentional apply is still running for that environment (ask in PR/ops channel if unsure).
3. Dispatch `.github/workflows/terraform-force-unlock.yml`:

```bash
gh workflow run terraform-force-unlock.yml \
  -f environment=staging \
  -f lock_id='<LOCK_UUID>'
```

4. After success, re-run the failed plan/apply workflow.

## Danger

- Unlock during a live apply can corrupt state.
- Do not delete S3 lock objects manually unless an admin explicitly instructs it.

## Done when

Workflow succeeded and the original job can acquire the lock again.
