# EPIC-010: Order Management

| Attribute | Value |
|-----------|-------|
| **Epic ID** | EPIC-010 |
| **Domain** | Commerce & Fulfilment |
| **Priority** | P0 |
| **Status** | Draft |
| **Owner** | Platform Team |

---

## Overview

EPIC-010 is the core commerce engine of Namma MedMate - everything from adding medicines to a cart through smart pharmacy selection, prescription quote broadcasting, order placement, payment, live status tracking, cancellation, refunds, and admin oversight. The order lifecycle spans multiple actors: the customer assembles a cart and pays, the pharmacy accepts and packs the order, a rider delivers it, and the admin team provides oversight, dispute resolution, and forced interventions. Two distinct order creation paths exist: the direct OTC path (smart-select pharmacy ? add items ? pay) and the Rx quote path (upload prescription ? broadcast to pharmacies ? receive quotes ? select ? pay). Both paths converge on the same order state machine and fulfilment flow.

---

## Stories

| Story ID | Title | Priority | Complexity | Status |
|----------|-------|----------|------------|--------|
| STORY-001 | Cart Management | P0 | L | Draft |
| STORY-002 | Smart Pharmacy Selection Engine | P0 | M | Draft |
| STORY-003 | Rx Quote Broadcast | P0 | L | Draft |
| STORY-004 | Order Placement and Payment | P0 | L | Draft |
| STORY-005 | Order Status Lifecycle | P0 | L | Draft |
| STORY-006 | Order Cancellation and Refund | P0 | M | Draft |
| STORY-007 | Reorder | P1 | S | Draft |
| STORY-008 | Admin Order Management | P0 | XL | Draft |

---

## Scope

**In scope:**
- Customer cart (add/remove/update, coupon, prescription, address, pharmacy switch)
- Smart pharmacy auto-selection scoring algorithm
- Rx quote broadcast to 3km radius pharmacies with 15-minute response window
- Order placement with Razorpay UPI/Card/COD and Namma Money wallet
- Full order state machine (PENDING_ACCEPTANCE ? DELIVERED)
- Live order tracking with ETA countdown
- Cancellation, refund routing (source/wallet), and eligibility checks
- Reorder from order history
- Admin order management: force-advance, dispute, reassign rider, notes, live feed

**Out of scope:**
- Pharmacy POS / billing system (EPIC-006)
- Rider dispatch and routing engine (EPIC-011)
- Namma Money wallet top-up (EPIC-012)
- Loyalty programme and referral credits (EPIC-013)
- B2B / institutional orders

---

## Order Lifecycle

```
Customer adds items ? Smart-select pharmacy
         ?
    Cart assembled (coupon, address, Rx if needed)
         ?
POST /api/v1/orders (place order)
         ?
   PENDING_ACCEPTANCE (pharmacy notified)
         ?
     ACCEPTED (pharmacy accepts, ?10 min)
         ?
      PACKING
         ?
  READY_FOR_PICKUP (OTP generated, rider assigned)
         ?
  OUT_FOR_DELIVERY
         ?
     DELIVERED (OTP verified)
```

---

## Pricing Rules Summary

| Component | Rule |
|-----------|------|
| Handling fee | Rs 5 (always) |
| Delivery fee | Rs 25, free if subtotal ? Rs 199 |
| NAMMA25 | 25% off item_total |
| FLAT50 | Rs 50 off item_total, min cart Rs 399 |
| FREEDEL | Free delivery (waives Rs 25 fee) |
| Namma Money | Applied first, up to order total |

---

## Key Dependencies

| Dependency | Type | Epic/System |
|------------|------|-------------|
| Razorpay payment gateway | External | Payment processing |
| EPIC-006 - Pharmacy POS/inventory | Bidirectional | Stock checks, order line items |
| EPIC-008 - Prescription management | Bidirectional | Rx validation at checkout |
| EPIC-009 - Teleconsult | Upstream | e-Rx links to cart |
| EPIC-011 - Rider dispatch | Downstream | Rider assignment |
| EPIC-012 - Namma Money wallet | Bidirectional | Wallet balance, deductions, refunds |
| Notification service (WhatsApp + Push) | Platform | Order confirmations, status updates |
| Auth & RBAC | EPIC-001 | JWT auth |
