# EPIC-008: Prescription Management

| Attribute | Value |
|-----------|-------|
| **Epic ID** | EPIC-008 |
| **Domain** | Prescription & Compliance |
| **Priority** | P0 |
| **Status** | Draft |
| **Owner** | Platform Team |

---

## Overview

EPIC-008 covers the full lifecycle of prescriptions on Namma MedMate - from customer upload and e-prescription generation through pharmacy review, admin compliance auditing, statutory drug registers, doctor verification, and regulatory filings. Prescriptions are the regulatory backbone of the platform: every Rx-only medicine dispensed must be traceable to a valid, verified prescription, and Schedule H1/X dispensing must satisfy Central Drugs Standard Control Organisation (CDSCO) and state Drugs Control Department requirements. The epic spans three primary actors - customers, pharmacies, and compliance admins - and creates a fully auditable chain from prescription receipt to dispense.

---

## Stories

| Story ID | Title | Priority | Complexity | Status |
|----------|-------|----------|------------|--------|
| STORY-001 | Prescription Upload and Storage | P0 | M | Draft |
| STORY-002 | Pharmacy Rx Queue and Dispense Workflow | P0 | L | Draft |
| STORY-003 | Admin Rx Compliance Audit | P0 | L | Draft |
| STORY-004 | Schedule H1/X Drug Register | P0 | M | Draft |
| STORY-005 | Doctor Registry and Verification | P1 | M | Draft |
| STORY-006 | Compliance Reports and Regulatory Filings | P1 | M | Draft |

---

## Scope

**In scope:**
- Customer prescription upload (PDF/JPG/PNG)
- e-Prescription storage from teleconsult (EPIC-009)
- Pharmacy Rx review queue with SLA tracking
- Admin compliance audit of all Rx-only dispenses
- Statutory Schedule H1 and X drug registers
- Doctor registry with NMC/State Board verification
- Regulatory filing generation and tracking

**Out of scope:**
- OCR engine implementation details (third-party, results surfaced via API)
- Live NMC API integration (flagged for v2; v1 = manual verification fallback)
- Video/audio storage for teleconsult (EPIC-009 scope)
- Drug recall workflow (referenced but defined in compliance filings story)

---

## Key Dependencies

| Dependency | Type | Epic/System |
|------------|------|-------------|
| AWS S3 private bucket | Infrastructure | DevOps |
| OCR service (AWS Textract / custom) | Service | Platform |
| Cashfree Webhook | External | EPIC-010 |
| Teleconsult e-Rx generation | Upstream | EPIC-009 |
| Pharmacy inventory / POS | Downstream | EPIC-006 |
| Notification service (WhatsApp + Push) | Service | EPIC-003 |
| Auth & RBAC | Platform | EPIC-001 |

---

## Compliance References

- **Drugs and Cosmetics Act 1940** - Schedule H, H1, X definition and dispensing rules
- **CDSCO Guidelines** - electronic prescription validity
- **IT Act 2000 / DPDP Bill 2023** - patient data privacy
- **WHO e-Prescription standards** - digital signature, RX-ID format
