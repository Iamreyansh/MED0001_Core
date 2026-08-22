# Production Readiness Audit

> Single source of truth for requirements-to-implementation mismatches found in the
> 22-epic + 6 cross-cutting audit (2026-08-22). Tracker status is recorded only in
> `AGENT-REQUIREMENT-IMPLEMENTATION.md`. This file records evidence, decisions, and
> verification criteria.

**Verdict:** **NOT PRODUCTION-READY** (remediation in progress, 2026-08-22). Tracker
stories are `staging-deployed` (128/129); `production-ready` waits for a prod
apply (D2). EPIC-003 STORY-003 stays `pending`. `make check`,
`make bruno-check`, LocalStack outbox IT, `make tf-fmt-check` /
`make tf-validate`, remote `tf-plan` for staging + prod, and
`make dependency-check` (OWASP, 0 vulns, CVSS fail ≥ 9.0) are green.
`make check-all` now invokes `dependencyCheckAnalyze` explicitly.
Staging apply with `image_tag=319b4cf0…` was a no-op (already on that SHA).
Same-SHA `make deploy-ecs ENV=staging` reached services-stable in ~3.5 min
with 0 failed tasks; `GET /api/v1/health` is UP afterward. Circuit breaker
rollback is on (min healthy 100%). RDS `med0001-staging-postgres` has
7-day encrypted automated backups and PITR through 2026-08-22T11:36:15Z
(no second instance was created). Prod apply and a live PITR restore
instance were not run.

**Audit date:** 2026-08-22  
**Scope:** All 153 requirement files, 22 epics / 129 stories, composition roots,
schema, infra, security, reliability, and release gates.

---

## Approved decisions

| ID | Decision | Source |
|----|----------|--------|
| D1 | Transactional outbox → SQS → dedicated workers, with DLQs and idempotent consumers | Architecture |
| D2 | Tracker `production-ready` means prod-deployed; `staging-deployed` is the staging gate | Product |
| D3 | `domains/payment` is the canonical Razorpay/RazorpayX owner | Architecture |
| D4 | Reserve inventory atomically at placement; deduct on pharmacy accept; release on cancel/timeout | Commerce |
| D5 | `UPLOADED` Rx may place an order; pharmacy must verify before fulfilment | Compliance |
| D6 | TCS = 1% of settlement GMV for all pharmacies; ₹5L threshold applies only to TDS 194-O | Finance |
| D7 | KYC uploads stay quarantined; signed GET and submit denied until clean scan | Security |
| D8 | Auto-KYC never activates a pharmacy; `admin_compliance` (super override) verifies documents | Compliance |
| D9 | EPIC-013 loyalty supersedes EPIC-002: points expire and may be redeemed | Product |
| D10 | No rider after 30 minutes → automatic cancel + canonical refund | Marketplace |

---

## Severity legend

| Severity | Meaning |
|----------|---------|
| **blocker** | Prevents safe production traffic or money movement |
| **high** | Incorrect business / compliance / security outcome in a live path |
| **medium** | Material AC gap, stub, or operational risk |
| **low** | Doc drift, polish, or documented ponytail |

---

## Cross-cutting mismatches

| ID | Epic / Story | Sev | Requirement | Evidence | Impact | Decision | Owner | Verification |
|----|--------------|-----|-------------|----------|--------|----------|-------|--------------|
| X1 | Reliability / EPIC-020 | blocker | Outbox publishes to SQS | `PlatformConfig` in-process consumers; `SqsEventDispatcher` marks published after local accept; no `SendMessage` | Domain events never reach worker | D1 | `platform/messaging`, `apps/api`, `apps/worker` | LocalStack IT: DB commit → SQS → worker ack |
| X2 | Reliability | blocker | Failed messages stay for retry/DLQ | `CustomerNotificationRequestedHandler` swallows errors; `KycMalwareScanPoller` deletes in `finally` | Poison/transient failures lost | D1 | `apps/worker` | Failure leaves message visible; DLQ after max receive |
| X3 | Reliability | blocker | Scheduler multi-instance safety | Most `@Scheduled` jobs use unclaimed `SELECT`; only wallet expiry uses `SKIP LOCKED` | Duplicate refunds, payouts, reminders on scale | D1 | platform + domains | Two workers cannot claim same row |
| X4 | EPIC-017 | blocker | Channel send from domain events | Only in-app PUSH handler; producers write unused event types; all providers stub | OTP, order, rider, refill, ops pages never delivered | D1 | `domains/notification` | Outbox SMS/WA/push/email invoke send services |
| X5 | EPIC-022 / 012 | blocker | Single Razorpay stack | Dual clients/webhooks in payment, order, integration | Double-process or missed ledger | D3 | `domains/payment` | One webhook URL updates payment + ledger + order |
| X6 | EPIC-010 / 006 | blocker | Live stock at checkout | Placement read-only; no reserve/decrement | Oversell | D4 | `domains/order`, `domains/inventory` | Concurrent qty=1: one succeeds, one `ITEMS_OUT_OF_STOCK` |
| X7 | EPIC-010 | blocker | Rx quote checkout-ready cart | `RxQuoteBroadcastService.toCartItems` uses random `product_id` | Rx path cannot place | D4 | `domains/order` | Quote select → place order IT |
| X8 | EPIC-003 / kernel | blocker | Real S3 presigned URLs | `PlatformConfig.localPresignedUrlService` is only bean; `MAX_UPLOAD_BYTES` unused | Uploads/downloads broken in prod | D7 | `apps/api`, kernel | Staging GET URL is `https://*.amazonaws.com` |
| X9 | EPIC-017 | blocker | Webhook secrets not defaults | `NotificationWebhookAuth` defaults `test_*` secrets; not in ECS | Forged SMS/email/WA callbacks | Fail-closed secrets | `domains/notification`, terraform | Prod boot fails on default secrets |
| X10 | EPIC-012 | blocker | Payment `Idempotency-Key` unique | `payment.idempotency_key` nullable, no unique index; initiate ignores header | Duplicate Razorpay orders | D3 | `domains/payment` | Replay initiate returns same payment |
| X11 | EPIC-019 | blocker | Event-driven rule execution | No fan-out; `StubActionExecutor`; workflows ignore kill switch | Automation is admin shell only | D1 | `domains/automation` | Outbox trigger evaluates stored rules and executes real action |
| X12 | EPIC-020 | blocker | Real SLO metrics | `StubMetricSourceAdapter` constants; infra alarms have no SNS subscription | Command center shows demo health | Real adapters | `domains/observability-ops`, terraform | Metrics sourced from orders/payments/ALB |
| X13 | Infra | blocker | Health reflects dependencies | `/api/v1/health` always UP | Broken deploy can pass smoke | Readiness | `apps/api` | Health DOWN without DB |
| X14 | EPIC-015 / 012 | high | Support refunds hit finance | `SupportBridgeConfig.stubSupportRefundPort` fake txn | Dispute “refund” never moves money | D3 | `apps/api` | Approve dispute creates `refund` + ledger |
| X15 | EPIC-008 | high | Order → Rx queue | `PharmacyRxQueueService.enqueue` unused | Pharmacy never sees marketplace Rx | D5 | `domains/order` | Place Rx order creates queue row |
| X16 | EPIC-005 / 010 | high | Price ceiling at checkout | `StubPriceCeilingAdapter` no-op | Over-ceiling checkout | Enforce | `apps/api` | Placement returns `PRICE_CEILING_VIOLATED` |
| X17 | EPIC-013 | high | Coupon redemption + eligibility | `recordRedemption` unused; `applyForCart` skips rules | Budget/analytics stale; scoped coupons bypass | Wire at placement | `domains/order`, `domains/marketing` | Placement records redemption and rejects ineligible codes |
| X18 | EPIC-012 | high | TCS 1% of GMV | `SettlementCalculator` applies only above ₹5L | Under-collected TCS | D6 | `domains/pharmacy` | ₹52k GMV settlement TCS = ₹520 |
| X19 | EPIC-007 | high | GST after discount | Checkout GST from undiscounted lines | Incorrect GST invoices | Fix formula | `domains/pos` | Discounted cart GST matches taxable value |
| X20 | EPIC-021 | high | Admin invite completion | Email stub; no token consume in auth | Staff cannot onboard | Wire email + auth | `domains/settings`, `domains/auth` | Invite token sets password and activates |
| X21 | EPIC-011 | high | Live assignment distance + shared SSE | `distanceKm` stub; in-memory SSE | Wrong dispatch; tracking broken multi-instance | Maps + Redis pub/sub | `apps/api`, `domains/rider` | Assignment uses Maps; SSE fans out across instances |
| X22 | Schema | high | Payment/support/CRM FKs + append-only | Missing FKs; several logs lack triggers | Orphans; mutable audit | Add constraints | `db/migration` | Migration applies; illegal UPDATE fails |
| X23 | Security | high | Audit role isolation + real archive | Any admin reads all; `LoggingAuditArchiveAdapter` | Cross-domain PII leak; fake Glacier | Filter + S3 | `domains/settings` | Finance cannot read KYC audit; archive writes object |
| X24 | Security | high | Deletion wipes linked PII | Anonymiser updates `customers` only | Addresses/Rx remain | Pseudonymize linked rows | `domains/customer` | Addresses/Rx PII wiped or unlinked |
| X25 | Tests | high | AC matrix + SQS/Bruno gates | 30 ITs; Bruno unused; no LocalStack | “100% JaCoCo” ≠ acceptance | Add gates | `testing`, CI | Matrix + LocalStack + Bruno in `make check-all` |

---

## Epic-by-epic status (audit vs tracker)

| Epic | Stories | Audit | Highest remaining class |
|------|---------|-------|-------------------------|
| EPIC-001 Auth | 5 | Partial | SMS stub, invite MFA bootstrap, admin session admin |
| EPIC-002 Customer | 5 | Partial | Notify delivery, CSV export, payment-method active-order guard |
| EPIC-003 KYC | 5 | Partial | Presign, quarantine, no auto-activate, penny-drop stub |
| EPIC-004 Pharmacy ops | 5 | Partial | Directory metrics, ratings zeros, RazorpayX stub |
| EPIC-005 Catalogue | 5 | Partial | Ceiling + stock decrement + geo search |
| EPIC-006 Inventory | 6 | Partial | Free-plan GRN, PO dispatch, reservation |
| EPIC-007 POS | 5 | Partial | GST/MRP, ONLINE ledger, PDF/CDN |
| EPIC-008 Prescription | 6 | Partial | Queue enqueue, schedule catalogue, S3 exports |
| EPIC-009 Teleconsult | 4 | Partial | Queue/slot assignment, phone ops, PDF |
| EPIC-010 Orders | 8 | Partial | Rx checkout, reserve, Razorpay, no-rider refund |
| EPIC-011 Rider | 8 | Partial | Distance, SSE, payout instrument |
| EPIC-012 Finance | 9 | Partial | Dual webhook, TCS, wallet ledger, unhold |
| EPIC-013 Marketing | 6 | Partial | Redemption, attribution, campaign dispatch |
| EPIC-014 CRM | 8 | Partial | SaaS payment, module enforce, PDFs |
| EPIC-015 Support | 5 | Partial | Refund stub, agent bootstrap, CSAT |
| EPIC-016 Analytics | 6 | Partial | S3 export, KPI math, acquisition stub |
| EPIC-017 Notifications | 6 | Partial | Dispatch worker, live providers |
| EPIC-018 Schedule | 5 | Partial | Push consumer, supply double-decrement |
| EPIC-019 Automation | 8 | Partial | Fan-out, real actions, kill switch |
| EPIC-020 Observability | 3 | Partial | Real metrics, paging, SNS |
| EPIC-021 Settings | 5 | Partial | Invite complete, config consumers |
| EPIC-022 Integrations | 6 | Partial | Consumer bridges, secrets |

---

## Per-epic findings (condensed)

### EPIC-001 Authentication
SMS is `LoggingSmsSender`. Admin invite tokens are unused. Cross-user session admin, GeoIP, and Redis session mirror missing. MFA enrollment for new `admin_super` depends on invite completion.

### EPIC-002 Customer
Core profile/address/wallet implemented. Admin notify always `delivered: false`. CSV export stub. `PaymentMethodInActiveOrderPort` always false. Referral cancel consumer missing. Loyalty redeem/expiry kept (D9).

### EPIC-003 Pharmacy KYC
Upload/admin APIs exist. Prod GET URLs are `local.invalid`. Request-path scan is no-op. Auto-KYC must never activate (D8). Penny-drop is stub. Expiry alerts not dispatched.

### EPIC-004 Pharmacy operations
Directory metrics table never written. Order metrics bridge zeros fill/ratings. RazorpayX stub. Pharmacy `GET /admin/zones` unwired (conflicts with rider zones).

### EPIC-005 Catalogue
CRUD/search/mappings solid. Checkout ceiling stub. Mapping stock never decremented. lat/lng search ignored. CUSTOM SKU search empty.

### EPIC-006 Inventory
POS FEFO wired. Free-plan GRN cannot create distributors. `inventory.po.sent` unconsumed. Online visibility requires `master_medicine_id`. No reservation API.

### EPIC-007 POS
Checkout/Khata/offers work. Discount does not reduce GST. `mrp_savings` always 0. No ONLINE invoices. PDF/CDN stub.

### EPIC-008 Prescription
Upload + register schema exist. Order never enqueues queue. Schedule heuristics stub. POS dispense stub. Exports local tmp. Delete does not purge S3.

### EPIC-009 Teleconsult
Doctor CRUD + e-Rx issuance work. No NOW-queue or scheduled-slot assignment. Admin APIs omit `patient_phone`. PDF stub. `CONSULT_NOT_ASSIGNED` not doctor-bound.

### EPIC-010 Orders
OTC cart → place → lifecycle ITs exist. Rx checkout broken. No reserve. Order Razorpay stub. No-rider is alert-only (must auto-refund, D10). Export local.

### EPIC-011 Rider
KYC/dispatch/COD/payout APIs exist. Assignment `distanceKm` stub. SSE in-memory. No rider payout instrument table. Tips = 0.

### EPIC-012 Finance
Ledger/overview ITs exist. Dual webhooks. TCS threshold wrong (D6). Capture commission global 8%. Wallet checkout skips ledger. No settlement unhold. `PAYMENT_FAILED` unused.

### EPIC-013 Marketing
Seeds + admin APIs exist. Redemption/attribution unwired. Campaign dispatch stub. Cart apply skips eligibility.

### EPIC-014 CRM
Plan/subscription/invoice logic unit-tested. Checkout/charge stubs. Module matrix off by default. No CRM ITs. Outbox notifications unconsumed.

### EPIC-015 Support
Ticket/dispute/KB ACs unit-tested. Refunds fake. Agent profiles not bootstrapped from `admin_staff`. No CSAT submit API.

### EPIC-016 Analytics
APIs + snapshots exist. Local file exports. Aggregated customer SUM overcount. `net_revenue` double-subtracts cancellations. Acquisition always ORGANIC.

### EPIC-017 Notifications
Channel APIs + unit ACs exist. No dispatch worker. Providers stub. OTP bypasses SMS service. Preference gate hardcodes TRANSACTIONAL.

### EPIC-018 Medicine schedule
CRUD/reminders/adherence unit-tested. Outbox types unconsumed; reminders marked SENT anyway. TAKEN + nightly double-decrements supply.

### EPIC-019 Automation
Admin CRUD/simulation/approvals exist. No trigger fan-out. Actions stub. Workflows ignore kill switch. Dedup/rate-limit in-memory.

### EPIC-020 Observability
Admin APIs + jobs exist. All six outbound ports stub. Critical page outbox unconsumed. Infra alarms have no SNS subscribers.

### EPIC-021 Settings
Staff/flag/config APIs exist. Invite/reset email stub. Config not consumed by wallet. Audit middleware uses role slug as actor name. Role-scoped reads missing.

### EPIC-022 Integrations
S2S APIs + unit ACs exist. Payment path does not use them. Accounting data empty. Comms control plane disconnected. Most secrets absent from Terraform.

---

## Infrastructure blockers

| Item | Evidence | Required |
|------|----------|----------|
| Placeholder Razorpay secrets deployable | `secrets.tf` `rzp_live_replace_me` | Reject placeholders at boot |
| Single-AZ RDS `db.t4g.micro`, 1 API + 1 worker | `data.tf`, `api.tf` | Multi-AZ + desired_count ≥ 2 |
| Valkey no TLS/auth | `data.tf` | Auth + transit encryption |
| No WAF, no ALB logs, Insights off | `network.tf`, `api.tf` | WAF + logs + metrics |
| SNS topic, no subscriptions | `observability.tf` | Pager/email/Slack subscription |
| EventBridge group empty | `messaging.tf` | Schedules or worker ownership |
| Health-only smoke | `smoke-remote.sh` | Auth + DB + queue canary |
| `make check` skips dependency scan | `Makefile` | `check-all` includes OWASP |

---

## Remediation phases

1. **Baseline** — this document + canvas + tracker reopen (D2).
2. **Async backbone** — outbox lease, SQS publish, worker router, DLQ (D1).
3. **Critical flows** — payment canon, inventory reserve, Rx queue, refunds, KYC quarantine (D3–D8, D10).
4. **Stubs** — providers, presign, secrets guards, automation/observability adapters.
5. **Data/security** — schema, GST/TCS/ledger, audit isolation, deletion, webhooks.
6. **Gates** — AC matrix, LocalStack ITs, Bruno in CI.
7. **Infra proof** — Terraform, staging drills, go/no-go.

A story is `production-ready` only after its ACs, the global production gates in this file, and a prod deploy pass. Staging ship uses `staging-deployed`.

Machine-readable AC inventory: `docs/requirements/acceptance-matrix.json` (129 stories).
LocalStack outbox publish IT: `apps/api` `OutboxSqsLocalStackIT`. Bruno idempotency is
enforced by `make bruno-check` (wired into `make check-all`).
