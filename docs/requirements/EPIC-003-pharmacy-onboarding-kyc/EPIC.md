# EPIC-003: Pharmacy Onboarding & KYC

| Attribute | Value |
|-----------|-------|
| **Epic ID** | EPIC-003 |
| **Domain** | Pharmacy Onboarding, Identity & Compliance |
| **Priority** | P0 |
| **Status** | Draft |

---

## Overview

EPIC-003 covers the complete lifecycle of bringing a new pharmacy onto the Namma MedMate platform - from initial self-service registration, document upload, automated and manual KYC verification, admin approval workflows, through to ongoing profile management. This is the foundational gateway epic: no pharmacy can participate in the marketplace, access SaaS features, or receive payouts until KYC is approved. It also governs changes to profile data and compliance documents post-activation, including bank account verification and operating-hours management.

---

## Goals

1. Enable any pharmacy owner in India to self-register on Namma MedMate within 5 minutes via web or mobile.
2. Collect all mandatory compliance documents (Drug Licence, GSTIN, FSSAI, PAN, ID) in a single structured flow.
3. Reduce manual KYC review load via automated GSTN/Drug Licence/FSSAI API checks.
4. Give admin operations team a clear, prioritised queue to review and approve/reject/suspend pharmacies.
5. Maintain an auditable trail of every KYC and profile change event.
6. Allow pharmacies to keep their profile, operating hours, tax configuration, and bank account up-to-date post-activation.

---

## Scope

### In Scope
- Pharmacy registration (initial signup form, email OTP verification)
- KYC document upload, management, and submission
- Automated KYC via government APIs (GSTN, Drug Licence registries, FSSAI)
- Admin KYC approval / rejection / suspension / reactivation workflows
- Pharmacy profile self-service updates (business details, operating hours, tax info)
- Bank account verification via penny drop
- Profile completeness scoring
- Audit trail for all KYC and profile events
- Notifications to pharmacy (WhatsApp + email) at key lifecycle steps

### Out of Scope
- Customer-facing pharmacy discovery (EPIC-006)
- Subscription plan selection & billing (EPIC-007)
- Delivery zone polygon management (EPIC-009)
- Rider onboarding (EPIC-010)

---

## Stories

| Story ID | Title | Priority | Complexity | Status |
|----------|-------|----------|------------|--------|
| STORY-003-001 | Pharmacy Registration | P0 | M | Draft |
| STORY-003-002 | KYC Document Upload | P0 | M | Draft |
| STORY-003-003 | Auto KYC Verification | P0 | L | Draft |
| STORY-003-004 | KYC Status Management (Admin) | P0 | M | Draft |
| STORY-003-005 | Pharmacy Profile Update | P1 | M | Draft |

---

## Success Metrics

| Metric | Target |
|--------|--------|
| Registration ? KYC submission time (median) | < 15 minutes |
| Auto-KYC pass rate (% approved without manual review) | ? 60% |
| Admin KYC review SLA (business hours) | ? 24 hours |
| KYC rejection rate due to incomplete documents | < 10% |
| Profile completeness for active pharmacies (median) | ? 90% |
| Drug Licence expiry alerts sent ? 60 days before expiry | 100% |

---

## Dependencies

| Dependency | Description |
|------------|-------------|
| EPIC-001 - Auth & Identity | JWT issuance, role assignment, OTP service |
| EPIC-002 - Notification Service | WhatsApp, email, in-app notifications |
| EPIC-007 - Subscription & Billing | Plan initialisation (FREE) at activation |
| EPIC-009 - Zone Management | Zone assignment at KYC approval |
| External: GSTN API | GSTIN validation and business name fetch |
| External: State Drug Control APIs | Drug licence status and expiry check |
| External: FSSAI Portal API | FSSAI licence validation |
| External: RazorpayX | Penny drop bank verification |
| Infrastructure: S3/Cloud Storage | Private document bucket with signed URLs |
