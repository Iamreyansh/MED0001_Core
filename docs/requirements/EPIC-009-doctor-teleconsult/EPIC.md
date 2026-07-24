# EPIC-009: Doctor Teleconsult

| Attribute | Value |
|-----------|-------|
| **Epic ID** | EPIC-009 |
| **Domain** | Healthcare Services |
| **Priority** | P0 |
| **Status** | Draft |
| **Owner** | Platform Team |

---

## Overview

EPIC-009 delivers Namma MedMate's free teleconsult service - a doctor-on-demand feature that allows patients to speak with a qualified in-house doctor, receive clinical advice, and obtain a digital e-prescription, all at zero cost to the patient. The teleconsult service is the primary on-ramp for prescription medicines in the Rx quote flow: patients who need a prescription for a medicine can initiate a consult directly from their cart, and the issued e-prescription is automatically linked to their active order. The epic covers four dimensions: admin management of the in-house doctor roster, patient consult request and scheduling, session lifecycle management (call logging, status transitions), and e-prescription generation with digital signing and cart linkage.

---

## Stories

| Story ID | Title | Priority | Complexity | Status |
|----------|-------|----------|------------|--------|
| STORY-001 | Doctor Profile Management | P1 | M | Draft |
| STORY-002 | Consult Request and Scheduling | P0 | M | Draft |
| STORY-003 | Consult Session Management | P0 | M | Draft |
| STORY-004 | e-Prescription Generation and Linking | P0 | L | Draft |

---

## Scope

**In scope:**
- Admin management of in-house Namma MedMate teleconsult doctor roster
- Patient-facing consult request (NOW and scheduled slots)
- Consult queue management and doctor assignment via load-balancing
- Phone-call-based consult session lifecycle (audio only, v1)
- e-Prescription issuance with digital signature, RX-ID, and cart linkage
- Patient rating and feedback
- Advice-only consult (no medicines) with consult receipt

**Out of scope:**
- Video call / VoIP infrastructure (v2)
- Third-party telemedicine aggregator integration (v2)
- Specialty routing (dermatology, cardiology, etc.) - all consults go to general physicians in v1
- Doctor scheduling / shift management (availability is toggled manually in v1)
- Insurance billing for teleconsult (free service in v1)

---

## Key Dependencies

| Dependency | Type | Epic/System |
|------------|------|-------------|
| EPIC-008 STORY-001 - Prescription storage | Downstream | e-Rx stored in customer prescription wallet |
| EPIC-010 STORY-001 - Cart management | Bidirectional | Cart-mode consult links e-Rx to cart |
| Notification service (Push + WhatsApp) | Platform | Consult status updates to patient |
| Auth & RBAC | EPIC-001 | JWT auth for all endpoints |
| Doctor phone number (internal) | Configuration | Doctor calls patient on `patient_phone` |

---

## Teleconsult Call Flow (v1)

```
Patient requests consult
        ?
System assigns available doctor (load-balance: least-recent consult)
        ?
Doctor reviews patient info + symptoms (DOCTOR_REVIEWING)
        ?
Doctor calls patient's phone number (CALLING)
        ?
Call in progress (IN_CALL)
        ?
Call ends ? Doctor issues e-Prescription or advice_only note (COMPLETED)
        ?
e-Rx auto-linked to cart (if cart-mode) or saved to patient's prescription wallet
        ?
Patient rates the consult (optional)
```
