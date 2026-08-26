# EPIC-003: Pharmacy Onboarding & KYC

| Attribute | Value |
|-----------|-------|
| **Epic ID** | EPIC-003 |
| **Domain** | Pharmacy Onboarding, Identity & Compliance |
| **Priority** | P0 |
| **Status** | Draft |

---

## Overview

EPIC-003 covers bringing a pharmacy onto Namma MedMate: self-service registration, document upload, **manual admin KYC review**, approval workflows, and profile management. No pharmacy participates in the marketplace until KYC is approved. Government auto-verification APIs are out of scope.

---

## Goals

1. Enable pharmacy owners to self-register within 5 minutes.
2. Collect mandatory compliance documents (Drug Licence, GSTIN, FSSAI, PAN, ID).
3. Give admin ops a clear queue to review, approve, reject, or suspend pharmacies.
4. Maintain an auditable trail of KYC and profile changes.
5. Allow pharmacies to keep profile, hours, tax, and bank details up to date.

---

## Scope

### In Scope
- Pharmacy registration (signup, email OTP)
- KYC document upload, management, and submission
- Admin KYC approval / rejection / suspension / reactivation
- Pharmacy profile self-service updates
- Bank account verification (penny drop / format checks)
- Profile completeness scoring
- Audit trail; SMS/push notifications at key steps

### Out of Scope
- Automated government API KYC (GSTN/DigiLocker/FSSAI registries)
- Customer-facing pharmacy discovery
- Subscription billing
- Rider onboarding

---

## Stories

| Story ID | Title | Priority | Complexity | Status |
|----------|-------|----------|------------|--------|
| STORY-003-001 | Pharmacy Registration | P0 | M | Draft |
| STORY-003-002 | KYC Document Upload | P0 | M | Draft |
| STORY-003-004 | KYC Status Management (Admin) | P0 | M | Draft |
| STORY-003-005 | Pharmacy Profile Update | P1 | M | Draft |
