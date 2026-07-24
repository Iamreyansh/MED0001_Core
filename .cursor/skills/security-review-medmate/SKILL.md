---
name: security-review-medmate
description: Security checklist for Rx/PII, secrets, RBAC, webhooks for MedMate changes. Use after auth, upload, webhook, payment, or Rx changes.
---

# Security review (MedMate)

Use with agent `security-reviewer`. Produce BLOCK/WARN findings; do not “fix forward” silently.

## Checklist

- **Secrets**: none in repo or logs; staging/prod via Secrets Manager (or env injected from SM).
- **JWT**: RS256; permissions enforced server-side; pharmacy tenancy via `pharmacy_id` (or story equivalent).
- **PII / Rx**: no phone, name, address, Rx text, or Rx object URLs in structured logs — ids only.
- **Webhooks**: HMAC over **raw** body (`WebhookRawBodyFilter` / cached body); idempotent handling of provider event ids.
- **Uploads**: S3 presigned PUT/GET only; private buckets; no public ACLs; max 10 MB product rule; no large multipart through Lambda/APIGW.
- **RBAC**: story roles enforced; never trust client-sent role alone.

## Report format

```
## Security review: <scope>
Verdict: PASS | FAIL
- [BLOCK] …
- [WARN] …
```

## Severity

| Level | Examples |
|-------|----------|
| BLOCK | Secret leak, missing HMAC, PII in logs, broken RBAC/tenancy, public bucket |
| WARN | Missing story rate limit, incomplete audit trail, docs gap |

## Done when

No BLOCK findings (or each BLOCK has an explicit waiver in the PR).
