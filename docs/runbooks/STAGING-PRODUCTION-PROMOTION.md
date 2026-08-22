# Staging → production promotion

Recovery targets: **RPO ≤15 minutes**, **RTO ≤60 minutes**. D1–D20 remain authoritative.
Do not mark tracker rows `production-ready` until this runbook has evidence from a real production apply.

Audit canvas: open beside chat —
[/Users/sanskar-mac-mini/.cursor/projects/Volumes-SSD-codebase-medmate-MED0001-Core/canvases/production-integration-readiness.canvas.tsx](/Users/sanskar-mac-mini/.cursor/projects/Volumes-SSD-codebase-medmate-MED0001-Core/canvases/production-integration-readiness.canvas.tsx)

## Preconditions

1. Immutable candidate image tagged with git SHA (staging) then semver `vX.Y.Z` (prod).
2. Secrets replaced out-of-band: Razorpay/X, MSG91, FCM, SendGrid, WhatsApp, Maps, OCR/GSP. `replace_me` fail-closes boot.
3. `MEDMATE_INTERNAL_SERVICE_TOKEN` injected from `${env}/internal`.
4. `vars.AWS_CI_ROLE_ARN` set for PR quality-gates (deploy role only for terraform/deploy).
5. Reviewed Terraform plan for the target env (API+worker autoscaling, S3 versioning, outbox/SQS alarms).

## Staging proof

```bash
make tf-plan ENV=staging
# apply only after review
scripts/deploy-ecs.sh staging
make smoke-remote HEALTH_URL=https://core.api.staging.nammamedmate.com/api/v1/health
BRUNO_REQUIRED=1 HEALTH_URL=https://core.api.staging.nammamedmate.com/api/v1/health make bruno-run
```

Vendor sandbox proofs (retain logs): Razorpay capture + refund, RazorpayX payout, MSG91 OTP, FCM, WhatsApp, email, Maps geocode, OCR/GSP if enabled.

Drills (record timestamps for RPO/RTO):

- Queue/DLQ redrive on `med0001-staging-domain-events-dlq`
- Malware quarantine on `kyc/` and `prescriptions/` prefixes
- Ambiguous payout/refund replay via `provider_operation`
- RDS PITR restore into a scratch instance (RPO ≤15m, RTO ≤60m)
- S3 noncurrent version restore (object RPO)
- Outbox age alarm / HealthController DEGRADED when pending >15m

## Production promotion

1. `deploy-prod` with semver tag only (no mutable `:prod` overwrite on immutable ECR).
2. `scripts/deploy-ecs.sh prod` — does **not** force desired count to 1; autoscaling owns worker/API counts.
3. Authenticated smoke + `BRUNO_REQUIRED=1 make bruno-run` against `https://core.api.nammamedmate.com`.
4. Confirm CloudWatch alarms: domain-events age, DLQ, outbox age, RDS backup window.
5. Only then update `AGENT-REQUIREMENT-IMPLEMENTATION.md` rows to `production-ready`.

## Rollback

- ECS circuit breaker rollback is enabled.
- Re-deploy previous immutable semver tag.
- State unlock: `/tf-unlock` if a plan/apply lock sticks.

### Terraform blast radius

- Env: both
- Resources created/changed: internal secret, CI OIDC role, API+worker autoscaling, S3 versioning, SQS age/DLQ/outbox alarms, GuardDuty prefixes, Redis replica (prod), RDS final snapshot (prod)
- Rollback: revert PR; unlock via `/tf-unlock` if stuck
