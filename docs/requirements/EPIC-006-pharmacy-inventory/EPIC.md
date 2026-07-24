# EPIC-006: Pharmacy Inventory

| Attribute | Value |
|-----------|-------|
| **Epic ID** | EPIC-006 |
| **Module** | Pharmacy Dashboard - ERP SaaS |
| **Plan Availability** | Free (STORY-001-004), Growth+ (STORY-005-006) |
| **Priority** | P0 |
| **Status** | Draft |

---

## Overview

EPIC-006 covers the complete inventory management subsystem of the Namma MedMate Pharmacy Dashboard. It enables pharmacies to maintain a real-time stock ledger across products, batches, rack locations, purchases, and distributors. The system enforces FEFO (First Expiry First Out) dispensing, tracks expiry risk, and surfaces actionable reorder intelligence for Growth-plan pharmacies. Together these six stories form the foundational data layer that every other pharmacy module - POS, Reports, CRM - depends on.

---

## Stories

| Story ID | Title | Plan | Priority | Complexity | Status |
|----------|-------|------|----------|------------|--------|
| STORY-001 | Inventory Management - Stock master, product CRUD, visibility controls | Free+ | P0 | L | Draft |
| STORY-002 | Batch & Expiry Management - FEFO tracking, expiry alerts | Free+ | P0 | L | Draft |
| STORY-003 | Rack Location Management - Physical shelf mapping | Free+ | P1 | M | Draft |
| STORY-004 | Purchase / GRN Management - Distributor invoice entry, goods received | Free+ | P0 | XL | Draft |
| STORY-005 | Distributor Management - Supplier directory, price comparison | Growth+ | P1 | M | Draft |
| STORY-006 | Reorder Suggestions - Auto-reorder intelligence | Growth+ | P1 | L | Draft |

---

## Key Data Entities

| Entity | Owner Story | Description |
|--------|-------------|-------------|
| `PharmacyProduct` | STORY-001 | Per-pharmacy stock master record |
| `ProductBatch` | STORY-002 | Individual batch with expiry and quantity |
| `RackLocation` | STORY-003 | Physical shelf/bin in the pharmacy |
| `PurchaseGRN` | STORY-004 | Goods Received Note header |
| `PurchaseGRNItem` | STORY-004 | Line item on a GRN |
| `Distributor` | STORY-005 | Supplier/distributor directory entry |
| `DistributorSupplyItem` | STORY-005 | Product-distributor price mapping |
| `PurchaseOrder` | STORY-006 | Reorder PO raised to distributor |
| `PurchaseOrderItem` | STORY-006 | Line item on a PO |

---

## Dependencies

- **EPIC-001 - Master Medicine Catalog**: `master_medicine_id` links PharmacyProduct to a global medicine record.
- **EPIC-007 - Pharmacy POS & Billing**: Billing deducts from `ProductBatch.quantity_current` via FEFO.
- **EPIC-008 - Reports & Analytics**: Inventory value, expiry risk, and movement reports read from this epic's tables.
- **EPIC-002 - Customer App / Online Store**: `is_online_visible` flag (STORY-001) gates product visibility on the customer-facing store.
- **EPIC-010 - Notifications**: Expiry alerts and reorder push notifications are dispatched through the notification service.

---

## Plan Feature Gating Summary

| Feature | Free | Starter | Growth | Pro |
|---------|------|---------|--------|-----|
| Stock master (product CRUD) | ? | ? | ? | ? |
| Batch & expiry tracking | ? | ? | ? | ? |
| Purchase / GRN entry | ? | ? | ? | ? |
| Rack location management | ? | ? | ? | ? |
| Distributor directory | ? | ? | ? | ? |
| Price comparison | ? | ? | ? | ? |
| Reorder suggestions & POs | ? | ? | ? | ? |

---

## Notes

- All inventory mutations (stock in, stock out) are append-only ledger events; the current stock is always a computed aggregate.
- Multi-user pharmacies (Pro plan) must record `staff_id` on every mutation for audit.
- Inventory APIs use `pharmacy_id` derived from the authenticated JWT (`pharmacy_owner` / `pharmacy_staff`) - never accept it as a query parameter.
