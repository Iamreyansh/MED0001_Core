# Production Readiness Audit

> Single source of truth for requirements-to-implementation mismatches from the
> 22-epic + cross-cutting integration review (revalidated 2026-08-22). Tracker
> status lives only in `AGENT-REQUIREMENT-IMPLEMENTATION.md`. This file records
> evidence, decisions, remediations, and verification criteria.

**Verdict:** **GO FOR STAGING/PROD PROMOTION** — local/static remediations for
D1–D20 and X1–X30 are implemented and unit-covered. Tracker rows stay
`staging-deployed` / `in_progress` until a new SHA is on staging; `production-ready`
waits for a prod apply (D2). Live AWS, vendor credentials, SNS confirmation, and
PITR remain deploy-stage proofs.

**Audit date:** 2026-08-22 (revalidated)
**Scope:** All 154 requirement files, 22 epics / 129 stories, composition roots,
schema, infra (static), security, reliability, and local release gates.
**Baseline:** Current working tree (includes uncommitted remediations).

---

## Approved decisions

| ID | Decision | Source |
|----|----------|--------|
| D1 | Transactional outbox → SQS → dedicated workers, with DLQs and idempotent consumers | Architecture |
| D2 | Tracker `production-ready` means prod-deployed; `staging-deployed` is the staging gate | Product |
| D3 | `domains/payment` is the canonical Razorpay/RazorpayX owner | Architecture |
| D4 | Hybrid inventory: reserve sellable qty at placement; consume FEFO batches/on-hand at accept; release on cancel | Commerce (2026-08-22) |
| D5 | `UPLOADED` Rx may place an order; pharmacy must verify before fulfilment | Compliance |
| D6 | TCS = 1% of settlement GMV for all pharmacies; ₹5L threshold applies only to TDS 194-O | Finance |
| D7 | KYC uploads stay quarantined; signed GET and submit denied until clean scan | Security |
| D8 | Auto-KYC never activates a pharmacy; `admin_compliance` (super override) verifies documents | Compliance (reconfirmed) |
| D9 | EPIC-013 loyalty supersedes EPIC-002: points expire and may be redeemed | Product |
| D10 | No rider after 30 minutes → automatic cancel + canonical refund | Marketplace |
| D11 | Medicine supply: TAKEN decrements immediately; nightly decrements only unrecorded scheduled doses | Product (2026-08-22) |
| D12 | ONLINE pharmacy invoice + sales-ledger row is created on `DELIVERED` | Finance (2026-08-22) |
| D13 | Scheduled teleconsults auto-assign at slot time using LRU, with ops override | Product (2026-08-22) |
| D14 | Held settlements require an explicit audited unhold before release | Finance (2026-08-22) |
| D15 | Referral codes apply only during OTP signup | Product (2026-08-22) |
| D16 | `admin_finance` may read platform GMV/revenue analytics as specified | Product (2026-08-22) |
| D17 | CSAT is submitted via authenticated API used by app/deep-link | Product (2026-08-22) |
| D18 | Teleconsult ops endpoints expose decrypted patient phone with audit logging | Product (2026-08-22) |
| D19 | Coupon eligibility is identical at validate, cart apply, and placement | Product (2026-08-22) |
| D20 | Dispute refunds use `RefundService.issueManual` for the approved amount | Finance (2026-08-22) |

---

## Severity legend

| Severity | Meaning |
|----------|---------|
| **blocker** | Prevents safe production traffic or money movement |
| **high** | Incorrect business / compliance / security outcome in a live path |
| **medium** | Material AC gap, stub, or operational risk |
| **low** | Doc drift, polish, or documented ponytail |

---

## Cross-cutting mismatches (revalidated)

| ID | Epic / Story | Sev | Requirement | Evidence (re-audit) | Status | Decision | Verification |
|----|--------------|-----|-------------|---------------------|--------|----------|--------------|
| X1 | Reliability | blocker | Outbox publishes to SQS | Worker `DomainEventRouter` routes notification/KYC/order/automation families | **resolved** | D1 | LocalStack IT: DB commit → SQS → worker ack |
| X2 | Reliability | blocker | Failed messages stay for retry/DLQ | Notification handler rethrows; malformed parse fails closed | **resolved** | D1 | Failure leaves message visible; DLQ after max receive |
| X3 | Reliability | blocker | Scheduler multi-instance safety | API `SchedulerLeaseAspect` wraps `@Scheduled` | **resolved** | D1 | Second instance skips when lease held |
| X4 | EPIC-017 | blocker | Channel send from domain events | Router → EPIC-017 send services; dose SENT after notify | **resolved** | D1 | Outbox SMS/WA/push/email invoke send services |
| X5 | EPIC-022 / 012 | blocker | Single Razorpay stack | Order webhook aliases `PaymentService.handleWebhook` | **resolved** | D3 | One webhook updates payment + ledger + order |
| X6 | EPIC-010 / 006 | blocker | Live stock at checkout | Reserve + FEFO deduct/release + GRN project | **resolved** | D4 | Concurrent qty=1; accept consumes FEFO |
| X7 | EPIC-010 | blocker | Rx quote checkout-ready cart | Quoted `product_id` + enqueue via pharmacy queue | **resolved** | D4 | Quote select → place uses real product IDs |
| X8 | EPIC-003 / kernel | blocker | Real S3 presigned URLs | Staging/prod use `S3PresignedUrlService` | **resolved** | D7 | Staging GET URL is `https://*.amazonaws.com` |
| X9 | EPIC-017 | blocker | Webhook secrets not defaults | Boot guards + ECS comms secret wiring | **resolved** | Fail-closed | Prod boot fails on default secrets |
| X10 | EPIC-012 | blocker | Payment `Idempotency-Key` unique | V128 unique index; initiate generates key if missing | **resolved** | D3 | Replay initiate returns same payment |
| X11 | EPIC-019 | blocker | Event-driven rule execution | `AutomationTriggerConsumer` + JDBC dedup + real outbox actions | **resolved** | D1 | Outbox trigger evaluates stored rules |
| X12 | EPIC-020 | blocker | Real SLO metrics | `JdbcMetricSourceAdapter` + paging consumers | **resolved** | Real adapters | Metrics sourced from orders/payments |
| X13 | Infra | blocker | Health reflects dependencies | `HealthController` SELECT 1 → 503 DOWN | **resolved** | Readiness | Health DOWN without DB |
| X14 | EPIC-015 / 012 | high | Support refunds hit finance | `RefundService.issueManual` with dispute idempotency | **resolved** | D20 | Approve ₹100 dispute refunds ₹100 |
| X15 | EPIC-008 | high | Order → Rx queue | Placement goes through `PharmacyRxQueueService.enqueue` | **resolved** | D5 | Place Rx order creates queue row |
| X16 | EPIC-005 / 010 | high | Price ceiling at checkout | `JdbcPriceCeilingAdapter` wired at place | **resolved** | Enforce | Placement returns `PRICE_CEILING_VIOLATED` |
| X17 | EPIC-013 | high | Coupon redemption + eligibility | `applyForCart` shares validate rules; place re-checks | **resolved** | D19 | Ineligible codes rejected at cart and place |
| X18 | EPIC-012 | high | TCS 1% of GMV | `SettlementCalculator` D6-compliant | **resolved** | D6 | ₹52k GMV settlement TCS = ₹520 |
| X19 | EPIC-007 | high | GST after discount | `gstAfterDiscount` scales `gst_total` to taxable value | **resolved** | Fix formula | Discounted cart GST matches taxable value |
| X20 | EPIC-021 | high | Admin invite completion | `POST /auth/admin/complete-invite` activates INVITED staff | **resolved** | Wire email + auth | Invite token sets password and activates |
| X21 | EPIC-011 | high | Live assignment distance + shared SSE | Maps fallback + in-memory SSE | accepted ceiling | Maps + Redis pub/sub | ponytail: single-instance SSE; upgrade → Redis pub/sub |
| X22 | Schema | high | Payment/support/CRM FKs + append-only | V128–V130 constraints + ledger uniqueness | **resolved** | Add constraints | Migration applies; illegal UPDATE fails |
| X23 | Security | high | Audit role isolation + real archive | Middleware uses staff display name; archive path exists | **resolved** | Named actors | Audit `actor_name` is staff name, not role slug |
| X24 | Security | high | Deletion wipes linked PII | Addresses + payment methods now redacted | **resolved** | Pseudonymize | Addresses/Rx PII wiped or unlinked |
| X25 | Tests | high | AC matrix + SQS/Bruno gates | Matrix 129/1067; CI runs `bruno-check` + `make check` | **resolved** | Add gates | Bruno + matrix in quality-gates and deploy-main |
| X26 | EPIC-001 | blocker | OTP SMS delivered | `Msg91OtpSmsSender` HTTP POST; local still logs | **resolved** | Live MSG91 | Deployed profile requires MSG91 key |
| X27 | EPIC-003 | blocker | Auto-KYC never activates | Publishes `pharmacy.kyc.manual_review_required` | **resolved** | D8 | All-PASS verify leaves pharmacy PENDING_KYC |
| X28 | EPIC-014 | blocker | SaaS invoice checkout live | CRM checkout `@Primary` via payment Razorpay gateway | **resolved** | D3 | Invoice pay creates Razorpay order |
| X29 | EPIC-018 | high | Supply not double-decremented | Nightly decrements `slots − TAKEN` | **resolved** | D11 | TAKEN then nightly: only unrecorded slots decrement |
| X30 | Infra | blocker | Notification vendor secrets in ECS | Staging/prod `comms` secret + task injection | **resolved** | Fail-closed | Prod task definition includes required secrets |

---

## Epic-by-epic status (re-audit)

| Epic | Stories | Highest remaining class | Notes |
|------|---------|-------------------------|-------|
| EPIC-001 Auth | 5 | accepted | MSG91 HTTP + invite complete; live handset is deploy-stage |
| EPIC-002 Customer | 5 | accepted | Signup-only referral; payment-method active-order guard |
| EPIC-003 KYC | 5 | accepted | Manual review only; gov/penny-drop fail closed |
| EPIC-004 Pharmacy ops | 5 | accepted | Order-backed metrics; ratings empty until ratings table |
| EPIC-005 Catalogue | 5 | accepted | Ceiling + GRN project; geo/CUSTOM deferred |
| EPIC-006 Inventory | 6 | accepted | Hybrid reserve/FEFO/release |
| EPIC-007 POS | 5 | accepted | Discounted GST + ONLINE invoice on DELIVERED |
| EPIC-008 Prescription | 6 | accepted | Queue enqueue + verify-before-fulfil |
| EPIC-009 Teleconsult | 4 | accepted | Slot-time LRU assign; ops queue shows patient phone |
| EPIC-010 Orders | 8 | accepted | Canonical payment webhook; wallet ledger |
| EPIC-011 Rider | 8 | accepted ceiling | Single-instance SSE; Maps fallback documented |
| EPIC-012 Finance | 9 | accepted | Unhold + issueManual + TCS 1% |
| EPIC-013 Marketing | 6 | accepted | Identical coupon eligibility; campaign consumer |
| EPIC-014 CRM | 8 | accepted | Module matrix on; Razorpay checkout bridge |
| EPIC-015 Support | 5 | accepted | CSAT API + partial dispute refund |
| EPIC-016 Analytics | 6 | accepted | Finance read; net = GMV − refunds; live new customers |
| EPIC-017 Notifications | 6 | accepted | Router → send services; comms secrets |
| EPIC-018 Schedule | 5 | accepted | Nightly unrecorded doses only |
| EPIC-019 Automation | 8 | accepted | Trigger consumer + durable dedup + kill switch |
| EPIC-020 Observability | 3 | accepted | JDBC metrics + paging consumers |
| EPIC-021 Settings | 5 | accepted | Invite consume + named audit actors |
| EPIC-022 Integrations | 6 | accepted | Payment owns Razorpay; comms secret wired |

---

## Per-epic findings (condensed)

### EPIC-001 Authentication
`Msg91OtpSmsSender` HTTP POST on deployed profiles; local logs. `POST /auth/admin/complete-invite` activates INVITED staff. Live handset OTP is deploy-stage.

### EPIC-002 Customer
Signup-only referral apply (D15). Payment-method active-order EXISTS guard. Loyalty/referral cancel consumers on `order.cancelled`.

### EPIC-003 Pharmacy KYC
Auto-KYC publishes `pharmacy.kyc.manual_review_required` and never activates (D8). Gov/penny-drop fail closed. Quarantine GET denied until clean scan (D7).

### EPIC-004 Pharmacy operations
Order-backed metrics via `JdbcPharmacyOrderMetricsBridge`. Ratings stay empty until a ratings table exists.

### EPIC-005 Catalogue
Checkout ceiling + GRN project. Geo/CUSTOM search remain deferred.

### EPIC-006 Inventory
Hybrid reserve at place, FEFO consume at accept, release on cancel (D4). Last sellable unit blocks the second customer.

### EPIC-007 POS
`gstAfterDiscount` scales tax to taxable value. ONLINE invoice + sales-ledger row on `DELIVERED` (D12). PDF/CDN still local stub.

### EPIC-008 Prescription
Order path enqueues the compliance queue; pharmacy must verify before fulfilment (D5). POS dispense / local export stubs remain.

### EPIC-009 Teleconsult
Scheduled consults auto-assign at slot time (LRU). Ops queue exposes `patient_phone` (D18).

### EPIC-010 Orders
Payment module owns Razorpay webhook. Wallet debit writes the financial ledger. OTC place + last-unit reserve ITs exist.

### EPIC-011 Rider
Maps fallback + in-memory SSE accepted as a single-instance ceiling (X21). Redis pub/sub is the upgrade path.

### EPIC-012 Finance
Explicit audited settlement unhold (D14). Dispute refunds use `RefundService.issueManual` (D20). TCS = 1% of GMV (D6). Dual webhook URLs both hit payment.

### EPIC-013 Marketing
Coupon eligibility identical at validate / cart / place (D19). `OrderDeliveredCampaignConsumer` + outbox dispatch.

### EPIC-014 CRM
`medmate.crm.enforce-module-matrix: true`. Invoice checkout `@Primary` via payment Razorpay gateway.

### EPIC-015 Support
Authenticated CSAT submit API (D17). Partial dispute refunds via `issueManual`.

### EPIC-016 Analytics
`admin_finance` reads overview (D16). `netRevenuePaise` = GMV − refunds. Live `new_customers` from `customers.created_at`.

### EPIC-017 Notifications
`DomainEventRouter` fans out to send services + automation. Staging/prod `comms` secret injected.

### EPIC-018 Medicine schedule
Nightly decrement = `max(0, slots − TAKEN)` (D11).

### EPIC-019 Automation
Trigger consumer + durable dedup + kill switch. Actions persist via outbox executor.

### EPIC-020 Observability
JDBC metric source + paging consumers. Remediation ports remain operator-driven.

### EPIC-021 Settings
Invite consume + named audit actors. Reset/invite email still stub until SES/MSG91 OOB.

### EPIC-022 Integrations
Payment owns Razorpay. Comms secret wired. Accounting export remains empty until Tally/Zoho credentials.

---

## Infrastructure blockers

| Item | Evidence | Required |
|------|----------|----------|
| Placeholder Razorpay secrets deployable | `secrets.tf` `rzp_live_replace_me` | Reject placeholders at boot (code) + OOB replace |
| Notification vendor secrets missing | Staging/prod `comms` secret wired | OOB replace `replace_me` before first prod traffic |
| EventBridge group empty | `messaging.tf` | Worker/API `@Scheduled` ownership documented |
| Health-only smoke | `smoke-remote.sh` | Auth + DB + queue canary (deploy stage) |
| CI skips OWASP/Bruno | `bruno-check` now runs in CI; OWASP via `make check-all` | Keep OWASP on release |
| Prod apply / PITR drill | Not run | D2 deploy stage |

---

## Remediation phases

1. **Baseline** — this document + canvas + matrix generator + tracker reopen.
2. **Async backbone** — worker router, channel dispatch, DLQ, leases, automation/observability (D1).
3. **Critical flows** — payment canon, hybrid inventory, Rx queue, refunds, KYC D8, invite (D3–D8, D10, D14, D20).
4. **Stubs** — providers, secrets guards, POS/Rx/CRM adapters.
5. **Data/security** — schema, GST/ledger, audit isolation, deletion, webhooks.
6. **Gates** — AC matrix, LocalStack ITs, Bruno + OWASP in CI.
7. **Infra proof** — Terraform, staging drills, go/no-go (out of local-static scope).

A story is `production-ready` only after its ACs, the global production gates in this file, and a prod deploy pass. Staging ship uses `staging-deployed`.

Machine-readable AC inventory: `docs/requirements/acceptance-matrix.json`.
LocalStack outbox publish IT: `apps/api` `OutboxSqsLocalStackIT`. Bruno idempotency is
enforced by `make bruno-check` (wired into `make check-all`).
