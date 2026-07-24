# EPIC-014: CRM SaaS (Pharmacy ERP Subscriptions)

| Field | Value |
|---|---|
| Epic ID | EPIC-014 |
| Epic Name | CRM SaaS - Pharmacy ERP Subscriptions |
| Folder | `docs/requirements/EPIC-014-crm-saas/` |
| Status | In Progress |
| Owner | Product - SaaS / Revenue Team |
| Created | 2026-07-24 |
| Last Updated | 2026-07-24 |

---

## Overview

EPIC-014 covers Namma MedMate's B2B SaaS business line - the Pharmacy ERP platform sold as a monthly/annual subscription to pharmacy businesses. This epic encompasses the full SaaS revenue lifecycle: plan catalogue management, subscription lifecycle (subscribe ? upgrade ? downgrade ? cancel), billing and invoicing with GST compliance, a sales pipeline (lead-to-subscriber conversion), account health scoring, feature adoption metering, renewal and churn management, and SaaS revenue analytics (MRR, ARR, NRR, GRR, cohort retention). This is a separate revenue stream from the marketplace and is managed by dedicated CSM, sales, and finance roles within Admin HQ.

---

## Stories

| Story ID | Title | Status | Priority |
|---|---|---|---|
| STORY-001 | SaaS Plan Management | Planned | P0 |
| STORY-002 | Subscription Management | Planned | P0 |
| STORY-003 | SaaS Billing & Invoicing | Planned | P0 |
| STORY-004 | Lead Pipeline | Planned | P1 |
| STORY-005 | Account Health Scoring | Planned | P1 |
| STORY-006 | Feature Adoption Metering | Planned | P2 |
| STORY-007 | Renewal & Churn Management | Planned | P1 |
| STORY-008 | SaaS Revenue Analytics | Planned | P1 |

---

## SaaS Plan Tiers

| Plan | Price/Month | Seats | Key Limits |
|---|---|---|---|
| FREE | Rs 0 | 1 | Core modules only |
| STARTER | Rs 699 | 2 | Core modules |
| RETAIL_PRO | Rs 1,499 | 5 | Advanced modules |
| ENTERPRISE | Custom | Unlimited | All modules + custom |

> Annual billing = monthly - 10 (2 months free, ~17% discount).

---

## Roles Involved

| Role | Access |
|---|---|
| `admin_super` | Full access; plan pricing edits; override subscriptions |
| `admin_finance` | Invoices, revenue analytics, MRR/ARR reporting |
| `admin_operations` | Lead pipeline, account management |
| `admin_support` | Support tickets linked to accounts |
| `pharmacy_owner` | Manage own subscription, view invoices |

---

## Key Business Rules (Epic-Level)

1. New pharmacies start on FREE plan automatically on registration.
2. SaaS invoices include GST @18% (SAC code 9983 - software services).
3. Plan changes are audit-logged with reason and actor.
4. Premium modules are immediately locked on plan expiry (EXPIRED status).
5. Annual billing locks in 2 months free; downgrade is scheduled for next renewal only.
6. All SaaS metrics (MRR, ARR, NRR, GRR) are computed monthly and cached; require `admin_finance` or `admin_super` role.

---

## SaaS Metrics Definitions

| Metric | Formula |
|---|---|
| MRR | Sum of monthly recurring revenue from all ACTIVE subscribers |
| ARR | MRR - 12 |
| ARPA | MRR / Total Active Accounts |
| NRR | (MRR_end + Expansion ? Churn) / MRR_start - 100 |
| GRR | (MRR_end ? Churn) / MRR_start - 100 |
| Logo Churn | Churned Accounts / Start-of-Period Accounts - 100 |
| Quick Ratio | (New + Expansion) / (Contraction + Churn) |
| Magic Number | (MRR Growth - 4) / Previous Quarter S&M Spend |
| LTV | ARPA - Gross Margin / Churn Rate |
| CAC | Total S&M Spend / New Customers |

---

## Dependencies

| Dependency | Epic / Module |
|---|---|
| Payment Gateway | EPIC-008 Finance |
| Pharmacy Auth & Onboarding | EPIC-002 Auth / EPIC-003 Pharmacy |
| Notification Engine | EPIC-010 Notifications |
| Automation Engine | EPIC-011 Automations |
| Analytics / Reporting | EPIC-016 Analytics |
| GST / Tax Module | EPIC-008 Finance |
