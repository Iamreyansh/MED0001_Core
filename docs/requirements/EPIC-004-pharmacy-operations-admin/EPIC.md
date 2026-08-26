# EPIC-004: Pharmacy Operations (Admin View)

| Attribute | Value |
|-----------|-------|
| **Epic ID** | EPIC-004 |
| **Domain** | Admin Operations, Pharmacy Management |
| **Priority** | P0 |
| **Status** | Draft |

---

## Overview

EPIC-004 provides Namma MedMate's admin team with the operational tooling to manage, monitor, and act on all active pharmacies in the marketplace. It covers the pharmacy directory with search and filtering, performance metric tracking (fill rate, on-time prep, ratings), commission ledger and payout settlement management, storefront and zone control, and bulk communication/intervention capabilities. This epic is the nerve centre for the admin_operations, admin_finance, and admin_support teams - enabling them to maintain marketplace quality, ensure compliance, and manage financial flows with pharmacies at scale.

---

## Goals

1. Give admin_operations a single pane of glass to monitor all pharmacies' health, status, and performance in real time.
2. Enable admin_finance to manage commission structures, view settlement ledgers, and release/hold payouts with full traceability.
3. Allow admin_operations to control pharmacy online/offline status and zone assignments in response to operational events.
4. Enable bulk communication and intervention for platform-wide campaigns, policy enforcement, and emergency actions.
5. Surface pharmacy performance metrics to proactively identify underperformers and trigger corrective actions before escalation.
6. Provide a complete audit trail of all admin actions on pharmacies.

---

## Scope

### In Scope
- Pharmacy directory with rich filters, search, and CSV export
- Summary KPI chips (total active, pending KYC, GMV today, commission today)
- Per-pharmacy performance metrics (fill rate, on-time prep, cancel rate, ratings)
- Admin-side ratings and order views
- Commission ledger: view, change, settlement history
- Settlement release and hold
- TCS computation and net payout calculation
- Storefront online/offline toggle (admin override)
- Zone reassignment
- Catalogue pause (temporary item hiding)
- Admin notices and internal notes
- Call log recording
- Bulk actions (suspend, send notice, export)

### Out of Scope
- Pharmacy self-service operations (EPIC-003-005)
- Customer-facing pharmacy discovery (EPIC-006)
- Subscription billing (EPIC-007)
- Rider management (EPIC-010)
- Financial reconciliation and tax filing (EPIC-012)

---

## Stories

| Story ID | Title | Priority | Complexity | Status |
|----------|-------|----------|------------|--------|
| STORY-004-001 | Pharmacy Directory | P0 | M | Draft |
| STORY-004-002 | Pharmacy Performance Metrics | P0 | M | Draft |
| STORY-004-003 | Commission & Payout Management | P0 | L | Draft |
| STORY-004-004 | Storefront & Zone Control | P0 | S | Draft |
| STORY-004-005 | Admin Pharmacy Actions | P1 | M | Draft |

---

## Success Metrics

| Metric | Target |
|--------|--------|
| Admin response time to pharmacy performance alert | ? 2 hours |
| Settlement release SLA (net D+7 after settlement period) | 100% on time |
| Admin directory page load time (p95) | ? 1.5 seconds |
| Bulk action completion time for 100 pharmacies | ? 30 seconds |
| Commission change audit coverage | 100% changes logged |
| TCS deduction accuracy | 100% (zero tolerance) |

---

## Dependencies

| Dependency | Description |
|------------|-------------|
| EPIC-003 - Pharmacy Onboarding | Pharmacy records and KYC status data |
| EPIC-007 - Subscription & Billing | Plan data shown in admin directory |
| EPIC-008 - Orders | Order data for performance metrics and GMV calculations |
| EPIC-009 - Zone Management | Zone list and pharmacy zone assignments |
| EPIC-002 - Notifications | WhatsApp/email/in-app admin notices |
| External: CashfreePayout | Settlement payouts to pharmacy bank accounts |
| Infrastructure: Redis | Performance metric caching (computed daily) |
