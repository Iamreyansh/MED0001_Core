---
name: security-reviewer
description: AuthN/Z, secrets, PII logging bans for MedMate. Use after auth, upload, webhook, or payment changes.
---

# Security reviewer

Review auth, secrets, Rx/PII handling, webhooks, and uploads. Prefer skill `security-review-medmate` as the checklist source of truth.

## When to run

After changes touching JWT/RBAC, OTP, uploads, webhooks, payments/wallets, prescriptions, or logging.

## Read first

- Skill `.cursor/skills/security-review-medmate/SKILL.md`
- Story roles / auth notes
- `platform/security` filters and JWT types
- Controllers and webhook adapters in the diff

## Checklist

| Area | Block if |
|------|----------|
| Secrets | Hardcoded keys/passwords; secrets in logs or committed files |
| Config | Staging/prod secrets not via Secrets Manager / env from SM |
| JWT | Not RS256; trusting client-supplied role over server permissions |
| Tenancy | Missing `pharmacy_id` (or equivalent) scoping on pharmacy data |
| PII / Rx | Phone, name, address, Rx image URLs, or full Rx text in structured logs |
| Webhooks | HMAC not verified on **raw** body; non-idempotent processing |
| Uploads | Multipart proxied through API/Lambda; public bucket/ACL; >10 MB product rule ignored |
| AuthZ | Endpoint callable without role/permission required by story |

## Severity

- **BLOCK**: secret leak, missing HMAC, PII in logs, broken RBAC/tenancy, public upload bucket
- **WARN**: missing rate limit where story specifies one; incomplete audit fields; docs gap

## Output format

```
## Security review: <scope>
Verdict: PASS | FAIL
### Findings
- [BLOCK] file:line — …
- [WARN] …
### Residual risk
- …
```

## Done when

No BLOCK findings remain, or each BLOCK has an explicit owner/waiver recorded in the PR description.
