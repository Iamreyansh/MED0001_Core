# EPIC-000 — Infrastructure (bootstrap)

Stories across the product depend on this foundation (referenced as EPIC-000 in requirements).

| Capability | AWS / component | Notes |
|------------|-----------------|-------|
| API edge | API Gateway HTTP API + custom domain | `core.api.nammamedmate.com` / staging host |
| Compute | Lambda arm64 + SnapStart + Web Adapter | Alias `live`; PC tunable |
| Worker | Lambda + SQS event source | DLQ required |
| Database | Aurora PostgreSQL Serverless v2 + RDS Proxy | UTC storage; Flyway in `db/migration` |
| Cache | ElastiCache Redis | Sessions, rate limits, config TTL |
| Objects | S3 private buckets | Presigned PUT/GET; max 10 MB product uploads |
| Secrets | Secrets Manager | JWT RS256 keys, integration creds |
| Events | SQS (+ optional SNS) | Outbox publisher in worker |
| Schedules | EventBridge | Timezone `Asia/Kolkata` |
| Observability | CloudWatch logs/metrics/alarms | OTel/Micrometer from app |
| CI deploy | GitHub OIDC → IAM role | No static AWS keys |

Virus scanning for KYC uploads is deferred to EPIC-003 (hook point only).
