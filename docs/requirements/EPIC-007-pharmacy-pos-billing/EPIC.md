# EPIC-007: Pharmacy POS & Billing

| Attribute | Value |
|-----------|-------|
| **Epic ID** | EPIC-007 |
| **Module** | Pharmacy Dashboard - ERP SaaS |
| **Plan Availability** | Free (STORY-001-002, STORY-004), Starter+ (STORY-003), Growth+ (STORY-005) |
| **Priority** | P0 |
| **Status** | Draft |

---

## Overview

EPIC-007 covers the complete billing and point-of-sale subsystem of the Namma MedMate Pharmacy Dashboard. From real-time barcode-scan counter sales to GST-compliant invoice generation, customer credit (Khata) tracking, and promotional offers, this epic is the revenue engine of the pharmacy ERP. The POS is a Free-plan feature - available to every registered pharmacy from day one. Higher-tier features like Khata credit and promotional offers unlock on Starter and Growth plans respectively.

---

## Stories

| Story ID | Title | Plan | Priority | Complexity | Status |
|----------|-------|------|----------|------------|--------|
| STORY-001 | Counter Sale POS - Real-time cart and checkout | Free+ | P0 | XL | Draft |
| STORY-002 | GST Invoice Management - Generation, sharing, customization | Free+ | P0 | L | Draft |
| STORY-003 | Credit / Khata Management - Customer credit tracking | Starter+ | P1 | L | Draft |
| STORY-004 | Sales Ledger - Full audit trail of all sales | Free+ | P0 | M | Draft |
| STORY-005 | Pharmacy Offers & Discounts - Promotions and coupon engine | Growth+ | P2 | M | Draft |

---

## Key Data Entities

| Entity | Owner Story | Description |
|--------|-------------|-------------|
| `PosCart` | STORY-001 | Session-based shopping cart |
| `PosCartItem` | STORY-001 | Line item within a cart |
| `Invoice` | STORY-002 | Finalized sale invoice |
| `InvoiceSettings` | STORY-002 | Pharmacy invoice template config |
| `KhataEntry` | STORY-003 | Credit ledger entry (debit/credit) |
| `KhataRepayment` | STORY-003 | Repayment receipt against outstanding |
| `PharmacyOffer` | STORY-005 | Promotion/coupon definition |
| `OfferRedemption` | STORY-005 | Record of a coupon being applied |

---

## Dependencies

- **EPIC-006 - Pharmacy Inventory:** POS deducts `ProductBatch.quantity_current` using FEFO; `is_rx_only` and `is_loose_selling_enabled` flags gate cart behaviour.
- **EPIC-004 - Customer App / Online Orders:** COD payment method and online order handoff use the same invoice pipeline.
- **EPIC-010 - Notifications:** Invoice WhatsApp/SMS sharing and Khata payment reminders use the notification service.
- **EPIC-008 - Reports:** Sales ledger data feeds all financial and analytical reports.
- **EPIC-001 - Master Medicine Catalog:** Product HSN codes and GST slabs are sourced from here when linked.

---

## Plan Feature Gating Summary

| Feature | Free | Starter | Growth | Pro |
|---------|------|---------|--------|-----|
| Counter sale POS | ? | ? | ? | ? |
| GST invoice generation | ? | ? | ? | ? |
| Invoice PDF + share | ? | ? | ? | ? |
| Invoice template customization | ? | ? | ? | ? |
| Sales ledger | ? | ? | ? | ? |
| Credit / Khata | ? | ? | ? | ? |
| Offers & coupons | ? | ? | ? | ? |

---

## Notes

- The POS cart is stateless from the client perspective; all cart state is server-side. Mobile/tablet clients must use `cart_id` for all subsequent operations.
- Invoice numbers follow a sequential, financial-year-aware format and are never recycled.
- GST invoice compliance requires per-line HSN and GST breakdown by slab for GSTIN-registered pharmacies.
