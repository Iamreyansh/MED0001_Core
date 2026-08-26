# Production Readiness Audit

> Single source of truth for requirements-to-implementation mismatches from the
> 22-epic + cross-cutting integration review (revalidated 2026-08-22). Tracker
> status lives only in `AGENT-REQUIREMENT-IMPLEMENTATION.md`. This file records
> evidence, decisions, remediations, and verification criteria.

**Verdict:** **NO-GO FOR PRODUCTION** until staging vendor proofs, PITR/RPO/RTO
drills, and a reviewed production apply complete (D2). Code remediations for
leases, outbox poison, consumer/webhook inbox, provider-operation persist-
before-I/O, internal token injection, SSRF, PII wipe, FEFO fail-closed, OCR/
CRM fail-closed, and launch-scope gates are in this tree. Tracker
`production-ready` stays 0 until production evidence is retained.

**Audit date:** 2026-08-26 (integration remediation pass)
**Scope:** All 154 requirement files, 22 epics / 129 stories, composition roots,
schema, infra (static), security, reliability, and local release gates.
**Baseline:** Working tree at start of production-integration remediations.
**Launch policy:** D1–D20 govern story/AC conflicts. Production-ready means
safety + core-flow blockers closed; unfinished non-core capabilities stay
disabled until real adapters and proofs exist. Recovery target: RPO ≤15 min,
RTO ≤60 min.

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
| D21 | Core flows must work at launch; only optional external integrations (DigiLocker, live gov APIs, IRN/GSP, Zoho/Tally, advanced Maps directions, OCR automation) may remain fail-closed | Product (2026-08-26) |

---

## Severity legend

| Severity | Meaning |
|----------|---------|
| **blocker** | Prevents safe production traffic or money movement |
| **high** | Incorrect business / compliance / security outcome in a live path |
| **medium** | Material AC gap, stub, or operational risk |
| **low** | Doc drift, polish, or documented ponytail |

---

## Deduplicated mismatch register

Status values: `open` · `in_progress` · `resolved` · `accepted ceiling` · `deferred`.

| ID | Epic / Story | Sev | Requirement / decision | Evidence | Status | Intent | Remediation | Verification |
|----|--------------|-----|------------------------|----------|--------|--------|-------------|--------------|
| X1 | Reliability | blocker | D1 outbox → SQS | Job-specific leases + poison column in V131; claim skips poisoned | **resolved** | defect | Job-specific lease + poison/dead-letter outbox | LocalStack: commit → SQS → ack; poison does not block later rows |
| X2 | Reliability | blocker | D1 fail-closed + DLQ | DomainEventRouter throws on empty/unknown; DLQ after max receive | **resolved** | defect | Schema-validate; throw unless explicitly ignorable | Invalid payload stays visible; DLQ after max receive |
| X3 | Reliability | blocker | D1 scheduler safety | API sole `@Scheduled` owner; owner-checked lease release | **resolved** | defect | One scheduler owner; lease per invocation with release | Second instance skips when lease held |
| X4 | Reliability | blocker | D1 idempotent consumers | Durable `consumer_inbox` + DomainEventRouter claim | **resolved** | defect | Durable `(consumer, event_id[, channel])` inbox | Duplicate SQS delivery is a no-op |
| X5 | EPIC-022 / 012 | blocker | D3 single Razorpay | Dual webhook URLs both hit payment (good); integration webhook updates parallel tables | **accepted ceiling** | intentional | Dashboard points only at payment URL | One webhook updates payment + ledger + order |
| X6 | EPIC-010 / 006 | blocker | D4 live stock | FEFO fail-closed + conditional deduct; cancel uses `RELEASE` | **resolved** | defect | Fail accept if FEFO incomplete; use `RELEASE` | Concurrent qty=1; accept consumes FEFO; cancel restores |
| X7 | EPIC-010 | high | Rx quote checkout-ready | Quote select requires `product_id` (wired) | **resolved** | D4 | — | Quote select → place uses real product IDs |
| X8 | EPIC-003 / 008 | blocker | Presigned PUT + D7 | KYC/Rx/rider still proxy multipart through API; Rx has no GuardDuty | **open** | defect | Presigned PUT + quarantine before OCR/GET/use | Staging GET is `https://*.amazonaws.com`; dirty object denied |
| X9 | EPIC-017 | blocker | Webhook/vendor secrets | Webhook defaults fail-closed; vendor keys accept `replace_me`; Twilio absent | **open** | defect | Reject placeholder vendor keys; add Twilio | Prod boot fails on default/placeholder secrets |
| X10 | EPIC-012 | high | Payment idempotency | Unique key exists; initiate generates key if missing | **resolved** | D3 | Persist provider identity before I/O | Replay initiate returns same payment |
| X11 | EPIC-019 | high | Event-driven rules | Trigger consumer + JDBC dedup exist; most actions are outbox no-ops | **accepted ceiling** | intentional | Keep seeds INACTIVE until typed consumers | Outbox trigger evaluates stored rules; seeds stay off |
| X12 | EPIC-020 | blocker | Real SLO metrics | Fake P99 returns 0; auto-remediate false on deploy profiles | **resolved** | defect | Real latency source; disable fake self-heal | Metrics sourced from orders/payments/ALB |
| X13 | Infra | high | Health reflects deps | `HealthController` Redis + outbox age | **resolved** | defect | Health includes Redis/outbox age | Health DEGRADED when outbox stale |
| X14 | EPIC-015 / 012 | high | Support refunds | `RefundService.issueManual` wired | **resolved** | D20 | — | Approve ₹100 dispute refunds ₹100 |
| X15 | EPIC-008 | high | Order → Rx queue | Placement enqueues queue | **resolved** | D5 | Sync `prescription.status` on queue actions | Place Rx order creates queue row |
| X16 | EPIC-005 / 010 | high | Price ceiling | `JdbcPriceCeilingAdapter` at place | **resolved** | Enforce | — | Placement returns `PRICE_CEILING_VIOLATED` |
| X17 | EPIC-013 | high | Coupon eligibility D19 | Cart/place enforce first-order + max_redemptions under lock | **resolved** | defect | Server-side first-order + cap under lock | Ineligible codes rejected at cart and place |
| X18 | EPIC-012 | high | TCS 1% of GMV | `SettlementCalculator` D6-compliant | **resolved** | D6 | Matrix AC supersedes ₹5L TCS story text | ₹52k GMV settlement TCS = ₹520 |
| X19 | EPIC-007 | high | GST after discount | `gstAfterDiscount` scales tax | **resolved** | Fix formula | — | Discounted cart GST matches taxable value |
| X20 | EPIC-021 | high | Admin invite complete | `POST /auth/admin/complete-invite` exists | **resolved** | Wire email + auth | Password-reset consume still open (R21) | Invite token sets password and activates |
| X21 | EPIC-011 | high | Maps + SSE | Maps fallback + in-memory SSE | **accepted ceiling** | intentional | Redis pub/sub later | Documented single-instance SSE |
| X22 | Schema | high | FKs + append-only | V128–V130 constraints | **resolved** | Add constraints | — | Illegal UPDATE fails |
| X23 | Security | high | Named audit actors | Middleware names staff; pharmacy/catalogue writers use `unknown` | **resolved** | defect | Resolve actor from `admin_staff`/`pharmacy_staff` | Audit `actor_name` is staff name |
| X24 | Security | high | Deletion wipes PII | Addresses + payment methods redacted; Rx/consult/schedule/support remain | **resolved** | defect | Transactional pseudonymize remaining PII | Addresses/Rx/consult PII wiped or unlinked |
| X25 | Tests | blocker | AC + Bruno gates | `acceptance-ac-gate.py` + `bruno-run` + Trivy; verified still false until staging | **resolved** | defect | Execute Bruno + AC evidence mapping | Bruno + matrix fail CI when unverified launch ACs exist |
| X26 | EPIC-001 | high | OTP SMS | `Msg91OtpSmsSender` HTTP on deployed profiles | **accepted ceiling** | deploy-stage | Live handset proof | Deployed profile requires MSG91 key |
| X27 | EPIC-003 | high | D8 never activate | `activateAfterAutoKyc` is no-op (D8) | **resolved** | D8 | Delete dead activation helpers | All-PASS verify leaves pharmacy PENDING_KYC |
| X28 | EPIC-014 | blocker | SaaS checkout live | FailClosedSubscriptionPaymentAdapter on prod/staging | **deferred** | defect / deferred | Disable live CRM charging until webhook + `SubscriptionPaymentPort` | Invoice pay creates Razorpay order **and** marks PAID |
| X29 | EPIC-018 | high | Supply decrement | Nightly `slots − TAKEN` | **resolved** | D11 | — | TAKEN then nightly: only unrecorded slots decrement |
| X30 | Infra | blocker | Comms secrets in ECS | `comms` secret injected with `replace_me` placeholders | **open** | defect | OOB replace + boot reject | Prod task includes required secrets; boot fails on placeholders |
| R1 | Infra | blocker | Internal service token | SM + ECS injection + InternalServiceTokenFilter | **resolved** | defect | SM secret + ECS injection | Deployed API boots |
| R2 | Infra | blocker | CI AWS privilege | staging/ci.tf gha_ci role; quality-gates prefers AWS_CI_ROLE_ARN | **resolved** | defect | Split CI vs deploy roles; constrain OIDC | PR CI cannot mutate prod |
| R3 | Infra | blocker | Deploy desired count | deploy-ecs no longer overrides desired count | **resolved** | defect | Stop overriding desired count | Prod runs declared replica count |
| R4 | Infra | blocker | Immutable `:prod` tag | Digest/semver promote; no mutable `:prod` | **resolved** | defect | Digest promotion, not retag | Repeat prod promote succeeds |
| R5 | Infra | high | Recovery | No S3 versioning; `skip_final_snapshot=true`; RPO/RTO unproven | **open** | defect | Versioning + PITR drill | Restore within RPO 15m / RTO 60m |
| R6 | EPIC-012 | blocker | Duplicate money | provider_operation + webhook_inbox persist-before-I/O | **resolved** | defect | Persist provider id before I/O; webhook inbox | Retry after ambiguous success does not double-pay |
| R7 | EPIC-007 / 006 | high | Stock races | `tryDeductQuantity` WHERE qty >= ? | **resolved** | defect | `UPDATE … WHERE qty >= ?` | Concurrent last unit fails second writer |
| R8 | EPIC-010 | high | D10 no-rider | Code auto-cancels (matches D10); STORY-005 AC said alert-only | **resolved** | D10 | Matrix AC updated to D10 | 30m no-rider → cancel + refund |
| R9 | EPIC-002 | high | Payment-method guard | orders.saved_payment_method_id + guard | **resolved** | defect | `orders.saved_payment_method_id` | Delete blocked only for in-use method |
| R10 | EPIC-012 | high | Ledger holes | Checkout `debitForOrder` and order refunds skip ledger; legacy payout paths skip ledger | **resolved** | defect | Write ledger at source; retire legacy | Wallet debit/refund appear in ledger |
| R11 | EPIC-017 | blocker | Live delivery | `HttpVendorClients` skeletal; producers omit templates; rider/admin paging drop | **in_progress** | defect | Real payloads + templates + recipient types | Staging handset/inbox proof |
| R12 | EPIC-010/011 | blocker | Rider deliver → order.delivered | Rider JDBC bypass skipped invoice/loyalty/campaign | **resolved** | defect | `OrderDeliveryConfirmPort` → `OrderLifecycleService` | Rider deliver publishes `order.delivered` once |
| R13 | EPIC-008 | high | Stub OCR / schedule / POS | FailClosed OCR; catalogue+stock bridges; exports still local | **accepted ceiling** | defect / deferred | Wire catalogue + stock; fail-closed OCR in prod; S3 export | No invented doctors; H1/X from master |
| R14 | EPIC-009 | high | NOW queue + D18 | FIFO NOW drain in assignDueScheduled; D18 audit log on queue | **resolved** | defect | FIFO NOW drain + encrypt + audit | Doctor available → queued NOW assigned |
| R15 | EPIC-016 / 021 | high | Config / finance RBAC | Platform config unused at runtime; finance denied analytics HTTP | **resolved** | defect | Config port + D16 security | PATCH config affects wallet cap; finance reads overview |
| R16 | EPIC-004 | high | Directory metrics | `pharmacy_directory_metrics` never written; orders `status=ALL` SQL | **resolved** | defect | Nightly UPSERT + treat ALL as no filter | Directory GMV/orders non-zero |
| R17 | Marketing | high | Banner SSRF | Resolve-and-reject non-public; no redirect follow | **resolved** | defect | Resolve-and-reject non-public | Private IP URL rejected |
| R18 | EPIC-014 | deferred | CRM charging | Stub `SubscriptionPaymentPort`; seat usage hardcoded 0 | **deferred** | launch-scope | Disable paid subscribe until live | FREE bootstrap still works |
| R19 | EPIC-019 / 020 | deferred | Self-heal / seeds | Remediation stubs; most automation actions no-op | **deferred** | launch-scope | Seeds INACTIVE; playbooks operator-only | No fake self-heal in prod |
| R20 | EPIC-022 | deferred | IRN / accounting / gov live | Adapters exist; POS/ERP/comms not wired; no TF secrets | **deferred** | launch-scope | Keep stubs fail-closed | Payment + maps geocode only at launch |
| R21 | EPIC-021 | high | Password reset consume | AdminPasswordResetCompleteService + Bruno | **resolved** | defect | Mirror invite consume | Reset token activates staff |
| R22 | Infra | high | Autoscaling / Redis HA | API+worker autoscaling; Redis replica (prod) | **resolved** | defect | API/worker scaling + Redis failover | Worker scales on queue depth |

---

## Epic-by-epic status (re-audit)

| Epic | Stories | Highest remaining class | Notes |
|------|---------|-------------------------|-------|
| EPIC-001 Auth | 5 | accepted ceiling | MSG91 HTTP + invite complete; live handset deploy-stage |
| EPIC-002 Customer | 5 | high | Signup-only referral (D15); payment-method guard wrong; deletion incomplete |
| EPIC-003 KYC | 5 | blocker | D8 runtime OK; multipart + expiry-alert + dead activation helpers |
| EPIC-004 Pharmacy ops | 5 | high | Directory metrics never written; orders ALL filter; payout stub |
| EPIC-005 Catalogue | 5 | high | Ceiling wired; geo/CUSTOM deferred; Rx schedule port stub |
| EPIC-006 Inventory | 6 | blocker | Hybrid reserve live; FEFO/cancel movement defects |
| EPIC-007 POS | 5 | high | Discounted GST + ONLINE invoice; stock race + no checkout idempotency |
| EPIC-008 Prescription | 6 | blocker | Queue + D5 OK; OCR/schedule/stock/POS/export stubs |
| EPIC-009 Teleconsult | 4 | high | Slot-time LRU; NOW queue + D18 audit missing |
| EPIC-010 Orders | 8 | blocker | Payment webhook canon; D10 cancel; export local; stock/ledger holes |
| EPIC-011 Rider | 8 | high | X21 ceiling; notify drop; payout bank stub; automation assign unwired |
| EPIC-012 Finance | 9 | blocker | TCS 1%; unhold; duplicate-pay risk; ledger holes |
| EPIC-013 Marketing | 6 | high | D19 partial; max redemptions; campaign consumer not idempotent |
| EPIC-014 CRM | 8 | deferred | FREE bootstrap live; paid subscribe/invoice close deferred |
| EPIC-015 Support | 5 | high | CSAT + issueManual; agent roster empty; dual disputes |
| EPIC-016 Analytics | 6 | high | Local export; finance HTTP blocked; acquisition stub |
| EPIC-017 Notifications | 6 | blocker | Router exists; vendor adapters + producer payloads incomplete |
| EPIC-018 Schedule | 5 | high | D11 OK; jobs unlocked; refill pref ignored |
| EPIC-019 Automation | 8 | deferred | Consumer + dedup; seeds stay INACTIVE |
| EPIC-020 Observability | 3 | blocker | Fake P99; stub remediation; paging broken |
| EPIC-021 Settings | 5 | high | Invite consume; config unused; reset consume missing |
| EPIC-022 Integrations | 6 | deferred | Payment owns Razorpay; other vendors deferred / unwired |

---

## Launch scope (safe launch)

**Must work before first production traffic**

Auth OTP, pharmacy/admin login, customer profile/address/wallet, KYC upload+quarantine+manual activate, catalogue search (pincode/zone_id), cart/place/pay (UPI/COD/wallet), inventory reserve/FEFO/release, Rx upload+queue+verify-before-fulfil, rider assign/track/COD, refunds/settlements/ledger, notifications for order/OTP/KYC, support tickets/disputes, platform config/RBAC, health + outbox/SQS.

**Disabled or fail-closed until adapters exist**

CRM paid subscribe/invoice close, automation seed ACTIVE, observability auto-remediation, accounting/IRN/Zoho, DigiLocker, acquisition mix, Timescale/Influx, geo lat/lng-only search, CUSTOM SKU search, in-process bulk exports to local disk as the only store.

---

## Infrastructure blockers

| Item | Evidence | Required | Status |
|------|----------|----------|--------|
| Internal token | SM + ECS + filter | Injected env | resolved |
| Placeholder secrets | boot validators | Reject + OOB replace | resolved (boot) |
| CI vs deploy IAM | staging/ci.tf | Split roles | resolved |
| Deploy desired count | deploy-ecs.sh | Keep TF count | resolved |
| Immutable promote | deploy-prod crane | Digest/semver | resolved |
| S3 versioning / PITR | data.tf | Versioning + drill | versioning OK; drill pending |
| Auth smoke / Bruno | promotion runbook | Canary + BRUNO_REQUIRED | pending staging |
| Scheduler owner | API `@Scheduled` | Documented | resolved |
| Worker autoscaling | api.tf SQS target | Queue depth | resolved |
| Outbox age alarm | messaging.tf | Custom metric | alarm wired |

---

## Remediation phases

1. **Baseline** — this document + canvas + matrix/decision reconciliation.
2. **Platform blockers** — IAM, token, leases, outbox/SQS inbox, money reconciliation, upload/SSRF/PII.
3. **Core flows** — inventory/POS/order/finance/Rx/KYC/rider/teleconsult/notifications.
4. **Disable unfinished** — CRM pay, seeds, self-heal, accounting/IRN.
5. **Gates** — executed Bruno, AC evidence, OWASP, Docker-mandatory ITs.
6. **Proof** — staging vendor + PITR/RPO/RTO + prod apply (D2).

A story is `production-ready` only after its launch-scope ACs, the global gates in this file, and a prod deploy pass. Staging ship uses `staging-deployed`.

Machine-readable AC inventory: `docs/requirements/acceptance-matrix.json`.
Audit canvas: [production-integration-readiness.canvas.tsx](/Users/sanskar-mac-mini/.cursor/projects/Volumes-SSD-codebase-medmate-MED0001-Core/canvases/production-integration-readiness.canvas.tsx).
