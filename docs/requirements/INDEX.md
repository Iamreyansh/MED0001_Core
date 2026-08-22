# Namma MedMate - Backend Requirements Index

> **Platform:** Namma MedMate - Hyperlocal Medicine Delivery Marketplace + Pharmacy ERP SaaS  
> **Version:** 1.0  
> **Last Updated:** July 2026  
> **- 2026 Medmate India Technology Private Limited**

---

## Overview

Namma MedMate is a four-sided platform:

| Side | Description |
|------|-------------|
| **Customer App** | Patients order medicines (12-30 min delivery), upload Rx, consult doctors for free e-prescriptions, manage medicine schedules for their family |
| **Pharmacy Dashboard** | Pharmacy ERP SaaS - POS/billing, inventory, Rx management, CRM, reports (Plans: Free / Starter Rs 699 / Growth Rs 1499 / Pro Rs 2999) |
| **Admin HQ** | Super-admin control tower - orders, pharmacies, riders, finance, compliance, analytics, support, automation |
| **Autonomous Operations** | Event-driven rules engine that makes the platform self-running with human oversight only for exceptions |

---

## Global API Conventions

| Convention | Specification |
|-----------|---------------|
| Base URL | `/api/v1` |
| Auth | `Authorization: Bearer <JWT>` |
| Success Envelope | `{ "success": true, "data": {...}, "meta": {...} }` |
| Error Envelope | `{ "success": false, "error": { "code": "ERROR_CODE", "message": "..." } }` |
| Pagination | `page`, `limit` (default 20, max 100), `sort`, `order` (asc\|desc) |
| Pagination Meta | `{ "page": 1, "limit": 20, "total": 100, "has_next": true }` |
| IDs | UUID v4 |
| Timestamps | ISO 8601 UTC (`created_at`, `updated_at`, `deleted_at`) |
| Soft Delete | `deleted_at` nullable timestamp |
| Currency | All amounts in Indian Rupees (Rs), stored as integers in paise where needed |

## Auth Roles

| Role | Description |
|------|-------------|
| `customer` | Customer app user (patient/shopper) |
| `pharmacy_owner` | Pharmacy owner (full pharmacy access) |
| `pharmacy_staff` | Pharmacy employee (role-based module access) |
| `rider` | Delivery rider |
| `admin_super` | Platform super-admin (all permissions) |
| `admin_operations` | Operations admin (orders, pharmacies, riders, logistics) |
| `admin_finance` | Finance admin (settlements, payouts, refunds, taxes) |
| `admin_support` | Support admin (tickets, disputes, customers) |
| `admin_compliance` | Compliance admin (Rx audit, schedule registers, doctor registry) |

---

## Epic Registry

### ??? Foundation

| Epic | Title | Priority | Stories | Status |
|------|-------|----------|---------|--------|
| [EPIC-001](./EPIC-001-auth-identity/EPIC.md) | Authentication & Identity | P0 | 5 | Draft |
| [EPIC-002](./EPIC-002-customer-management/EPIC.md) | Customer Management | P0 | 5 | Draft |
| [EPIC-021](./EPIC-021-settings-admin/EPIC.md) | Settings & Platform Administration | P1 | 5 | Draft |

### ?? Pharmacy Side

| Epic | Title | Priority | Stories | Status |
|------|-------|----------|---------|--------|
| [EPIC-003](./EPIC-003-pharmacy-onboarding-kyc/EPIC.md) | Pharmacy Onboarding & KYC | P0 | 5 | Draft |
| [EPIC-004](./EPIC-004-pharmacy-operations-admin/EPIC.md) | Pharmacy Operations (Admin View) | P0 | 5 | Draft |
| [EPIC-005](./EPIC-005-master-catalogue/EPIC.md) | Master Catalogue | P0 | 5 | Draft |
| [EPIC-006](./EPIC-006-pharmacy-inventory/EPIC.md) | Pharmacy Inventory | P0 | 6 | Draft |
| [EPIC-007](./EPIC-007-pharmacy-pos-billing/EPIC.md) | Pharmacy POS & Billing | P0 | 5 | Draft |

### ?? Prescriptions & Teleconsult

| Epic | Title | Priority | Stories | Status |
|------|-------|----------|---------|--------|
| [EPIC-008](./EPIC-008-prescription-management/EPIC.md) | Prescription Management | P0 | 6 | Draft |
| [EPIC-009](./EPIC-009-doctor-teleconsult/EPIC.md) | Doctor Teleconsult | P1 | 4 | Draft |

### ?? Marketplace

| Epic | Title | Priority | Stories | Status |
|------|-------|----------|---------|--------|
| [EPIC-010](./EPIC-010-order-management/EPIC.md) | Order Management | P0 | 8 | Draft |
| [EPIC-011](./EPIC-011-rider-delivery/EPIC.md) | Rider Management & Delivery | P0 | 8 | Draft |
| [EPIC-018](./EPIC-018-medicine-schedule/EPIC.md) | Medicine Schedule (Customer) | P1 | 5 | Draft |

### ?? Finance & Payments

| Epic | Title | Priority | Stories | Status |
|------|-------|----------|---------|--------|
| [EPIC-012](./EPIC-012-payments-finance/EPIC.md) | Payments & Finance | P0 | 9 | Draft |

### ?? Growth & Marketing

| Epic | Title | Priority | Stories | Status |
|------|-------|----------|---------|--------|
| [EPIC-013](./EPIC-013-marketing-growth/EPIC.md) | Marketing & Growth | P1 | 6 | Draft |

### ?? CRM SaaS

| Epic | Title | Priority | Stories | Status |
|------|-------|----------|---------|--------|
| [EPIC-014](./EPIC-014-crm-saas/EPIC.md) | CRM SaaS (Pharmacy ERP Subscriptions) | P0 | 8 | Draft |

### ?? Support

| Epic | Title | Priority | Stories | Status |
|------|-------|----------|---------|--------|
| [EPIC-015](./EPIC-015-support-disputes/EPIC.md) | Support & Disputes | P1 | 5 | Draft |

### ?? Analytics & Reporting

| Epic | Title | Priority | Stories | Status |
|------|-------|----------|---------|--------|
| [EPIC-016](./EPIC-016-analytics-reporting/EPIC.md) | Analytics & Reporting | P1 | 6 | Draft |

### ?? Notifications

| Epic | Title | Priority | Stories | Status |
|------|-------|----------|---------|--------|
| [EPIC-017](./EPIC-017-notifications-comms/EPIC.md) | Notifications & Communications | P0 | 6 | Draft |

### ?? Automation & AI

| Epic | Title | Priority | Stories | Status |
|------|-------|----------|---------|--------|
| [EPIC-019](./EPIC-019-automation-rules-engine/EPIC.md) | Automation & Rules Engine | P1 | 8 | Draft |
| [EPIC-020](./EPIC-020-observability-self-healing/EPIC.md) | Observability & Self-Healing | P2 | 3 | Draft |

### ?? Integrations

| Epic | Title | Priority | Stories | Status |
|------|-------|----------|---------|--------|
| [EPIC-022](./EPIC-022-external-integrations/EPIC.md) | External Integrations | P0 | 6 | Draft |

---

## Story Count Summary

| Phase | Epics | Stories |
|-------|-------|---------|
| Phase 1 - Foundation + Core Marketplace | EPIC-001, 002, 003, 004, 005, 010, 011, 021 | ~46 |
| Phase 2 - Finance + Pharmacy ERP | EPIC-006, 007, 012, 014, 022 | ~33 |
| Phase 3 - Prescriptions + Growth | EPIC-008, 009, 013, 015, 017, 018 | ~32 |
| Phase 4 - Intelligence + Automation | EPIC-016, 019, 020 | ~17 |
| **Total** | **22 Epics** | **~130 Stories** |

---

## Implementation Phases (from Autonomous Operations Blueprint)

| Phase | Focus | Key Epics |
|-------|-------|-----------|
| **Phase 1** | Automation & Rules Engine + Activity Log + Simulation | EPIC-019 |
| **Phase 2** | Order/Dispatch Autopilot + Finance Loop (payouts, dunning, refunds) | EPIC-010, 011, 012 |
| **Phase 3** | CRM Success + Support Autopilots (health plays, triage, SLA escalation) | EPIC-014, 015 |
| **Phase 4** | AI Layer (forecasting, fraud, Rx, churn) + Key Integrations | EPIC-019, 022 |
| **Phase 5** | Full Observability, Self-Healing, Governance Hardening | EPIC-020 |

---

## Key Platform Business Rules

### Delivery & Fees
- Handling fee: **Rs 5** on any non-empty cart
- Delivery fee: **Rs 25** (free when cart subtotal ? Rs 199)
- One pharmacy per order (always)
- Delivery target: **12-30 minutes**

### Coupons
| Code | Type | Value | Condition |
|------|------|-------|-----------|
| NAMMA25 | Percentage | 25% off subtotal | None |
| FLAT50 | Flat Rs | Rs 50 off | Min cart Rs 399 |
| FREEDEL | Free delivery | Rs 25 waived | None |

### Finance
- Default pharmacy commission: **8%** (range 3-20%)
- TCS: **1% of GMV** (Section 194-O)
- GST on platform commission: **18%** (SAC 9983)
- Settlement cycle: **Weekly** (Mon-Sun)
- Rider COD limit: **Rs 2,000** per rider

### Pharmacy SaaS Plans
| Plan | Price/Month | Users | Key Features |
|------|------------|-------|--------------|
| Free | Rs 0 | 2 | POS, Inventory, Purchases, Invoice Settings |
| Starter | Rs 699 | 2 | + Prescriptions, Customers, Credit/Khata |
| Growth | Rs 1,499 | 5 | + Online Store, Reports, CRM, Reorder, Distributors |
| Pro | Rs 2,999 | Unlimited | + Hospital/IPD Suite, Self-Order Kiosk |

### Regulatory Compliance
- Schedule H: Prescription required
- Schedule H1: Prescription + special register (retain 3 years)
- Schedule X: Prescription + statutory register (retain 5 years)
- GSTR-8: Filed monthly by 10th of next month
- e-Invoicing: Required for B2B above Rs 5 crore annual turnover

---

## Story File Template Reference

Every story file (`STORY-XXX-name.md`) follows this structure:

1. **Metadata table** - Story ID, Epic, Priority, Complexity, Status
2. **Overview** - What this story delivers and its business value
3. **User Roles & Access** - Who can use this and how
4. **Business Rules** - Minimum 6 specific, testable rules
5. **API Endpoints** - Full spec per endpoint (method, path, auth, rate limit, request body, response, errors)
6. **Data Models** - Entity field tables with types, constraints, descriptions
7. **Acceptance Criteria** - Minimum 6 Given/When/Then criteria
8. **Dependencies** - Other stories/epics required
9. **Notes** - Edge cases and implementation hints

---

Production readiness mismatches and approved decisions live in [PRODUCTION-READINESS-AUDIT.md](./PRODUCTION-READINESS-AUDIT.md). Implementation status lives only in [AGENT-REQUIREMENT-IMPLEMENTATION.md](./AGENT-REQUIREMENT-IMPLEMENTATION.md).

*This index is the single source of truth for all backend requirement documents. All epics and stories are in Draft status and will be refined during sprint planning.*
