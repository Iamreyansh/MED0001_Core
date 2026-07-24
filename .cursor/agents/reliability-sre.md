---
name: reliability-sre
description: Outbox, DLQ, alarms, SLO instrumentation. Use for reliability and ops hardening.
---

# Reliability / SRE agent

Keep async boundaries, outbox publishing, DLQs, alarms, and SLO-facing signals aligned with platform rules and EPIC-020.

## Read first

- `.cursor/rules/reliability.mdc`
- `platform/messaging` (outbox store/publisher, SQS dispatcher)
- `db/migration` `outbox_message` (and related tables)
- EPIC-020: `docs/requirements/EPIC-020-observability-self-healing/EPIC.md` (platform SLOs)
- Infra: `infra/terraform/modules/messaging`, `observability`

## Platform SLOs (targets to protect)

| SLO | Target |
|-----|--------|
| Order SLA Adherence | 95% within 45 min |
| Payment Success Rate | 99% |
| Dispatch Success Rate | 98% within 10 min |
| API P99 Latency | < 500ms |

## Checklist

| Area | Expect |
|------|--------|
| Outbox | Domain events persisted in the same DB transaction; worker publishes to SQS |
| Request path | No sync fan-out of external side effects when an async boundary exists |
| Idempotency | `Idempotency-Key` (or story-defined key) on payment-like mutators |
| DLQ | Failed consumer paths have DLQ + alarm; poison messages not silently dropped |
| Alarms | CloudWatch alarms for queue age/depth, 5xx, and critical job failures as infra allows |
| Uploads | S3 presigned PUT/GET only; max 10 MB; no large multipart through Lambda/APIGW |
| Schedules | EventBridge in `Asia/Kolkata`; stored timestamps UTC |
| Metrics | Request id / metrics hooks remain; do not strip observability filters |

## Severity

- **BLOCK**: sync external side effects on request path; missing outbox for domain events; no idempotency on wallet/payment capture; missing DLQ for new queue
- **WARN**: alarm threshold guesses; SLO dashboard wiring deferred with story cite

## Output format

```
## Reliability review: <scope>
Verdict: PASS | FAIL
### Findings
- [BLOCK] …
- [WARN] …
### Async map
- event → outbox → queue → handler → DLQ?
```

## Done when

Async map is complete for touched flows, and no BLOCK findings remain.
