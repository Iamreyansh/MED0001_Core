# EPIC-000 — Infrastructure (bootstrap)

Stories across the product depend on this foundation (referenced as EPIC-000 in requirements).

| Capability | AWS / component | Notes |
|------------|-----------------|-------|
| API edge | ALB + ACM + Route 53 | staging `staging-core.api…`; prod `core.api.nammamedmate.com` |
| Compute | ECS Fargate arm64 (public subnets, no NAT) | API + worker per env; prod via `release-*` tag promote |
| Worker | Fargate long-poll SQS | DLQ required |
| Database | RDS PostgreSQL `db.t4g.micro` single-AZ | UTC storage; Flyway in `db/migration` |
| Cache | ElastiCache Valkey `cache.t4g.micro` | Sessions, rate limits, config TTL |
| Objects | S3 private buckets | Bucket `med0001-{env}-uploads-105927215604`; keys via `StorageObjectKeys` prefixes (`avatars/`, `exports/`, `prescriptions/`, `kyc/`, …) — never bucket root; CDN `cdn.nammamedmate.com`; Presigned PUT/GET; max 10 MB |
| Secrets | Secrets Manager | JWT RS256 keys, DB credentials |
| Events | SQS (+ EventBridge Scheduler group) | Outbox consumer in worker |
| Schedules | EventBridge Scheduler | Timezone `Asia/Kolkata` |
| Observability | CloudWatch logs/metrics/alarms + Budgets | 7-day log retention |
| CI deploy | GitHub OIDC → IAM role | No static AWS keys |
| Terraform state | `s3://terraform-locks-105927215604/MED0001/` | `use_lockfile = true` (no local state) |

Virus scanning for KYC uploads is deferred to EPIC-003 (hook point only).
