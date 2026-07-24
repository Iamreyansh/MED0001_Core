# EPIC-005: Master Catalogue

| Attribute | Value |
|-----------|-------|
| **Epic ID** | EPIC-005 |
| **Domain** | Product Catalogue, Medicine Taxonomy, Price Controls |
| **Priority** | P0 |
| **Status** | Draft |

---

## Overview

EPIC-005 establishes and manages the canonical medicine catalogue for Namma MedMate - the authoritative source of truth for all medicines, health products, categories, and their regulatory classifications available on the platform. The master catalogue is admin-curated and serves as the template from which pharmacies map their inventory. It supports rich product metadata (salt composition, form, schedule classification, HSN/GST codes, MRP), full-text search for customers and pharmacies, substitute medicine linkages, and admin-controlled price ceilings for essential medicines. Pharmacies map master SKUs to their own inventory to appear on the online storefront; custom POS SKUs not in the master catalogue remain offline only.

---

## Goals

1. Maintain a clean, de-duplicated master medicine database with consistent nomenclature and regulatory metadata.
2. Enable customers to discover medicines by name, salt, brand, or category with relevance-ranked, location-aware search results.
3. Classify medicines by therapeutic category and regulatory schedule (OTC, H, H1, X) to enforce prescription gating.
4. Enforce MRP ceilings for essential medicines and alert admin to price violations.
5. Allow pharmacies to map master SKUs to their inventory, controlling what appears on their online store.
6. Support bulk catalogue operations (admin roll-outs, bulk mapping to pharmacies).

---

## Scope

### In Scope
- Admin CRUD for master medicine records
- Category management (create, update, reorder, soft delete)
- Schedule classification rules (OTC, H, H1, X)
- Full-text medicine search (customer + pharmacy-scoped)
- Public medicine detail page
- Substitute medicine linking
- Availability check across pharmacies
- Admin price ceiling management and violation reporting
- Pharmacy catalogue mapping (map/unmap master SKUs to pharmacy inventory)
- Admin bulk-map tool

### Out of Scope
- Pharmacy custom POS SKUs (managed in EPIC-006 Pharmacy Inventory)
- Purchase orders and supplier management (EPIC-006)
- Batch and expiry tracking (EPIC-006)
- Drug interaction checking (future EPIC)
- Prescription validation (EPIC-008)

---

## Stories

| Story ID | Title | Priority | Complexity | Status |
|----------|-------|----------|------------|--------|
| STORY-005-001 | Medicine Master CRUD | P0 | M | Draft |
| STORY-005-002 | Category & Schedule Management | P0 | S | Draft |
| STORY-005-003 | Medicine Search & Discovery | P0 | M | Draft |
| STORY-005-004 | Price Ceiling Management | P1 | S | Draft |
| STORY-005-005 | Pharmacy Catalogue Mapping | P0 | M | Draft |

---

## Success Metrics

| Metric | Target |
|--------|--------|
| Medicine search p95 latency | ? 200ms |
| Search autocomplete p95 latency | ? 80ms |
| Master catalogue SKU count at launch | ? 50,000 SKUs |
| Pharmacy catalogue mapping coverage | ? 80% of top-demand SKUs per pharmacy |
| Price ceiling violation detection lag | ? 1 hour after pharmacy updates price |
| Banned medicine removal from all storefronts | ? 30 seconds |

---

## Dependencies

| Dependency | Description |
|------------|-------------|
| EPIC-003 - Pharmacy Onboarding | Pharmacy must be ACTIVE to create catalogue mappings |
| EPIC-006 - Pharmacy Inventory | Pharmacy inventory updates stock_quantity on sales |
| EPIC-008 - Orders | Order placement checks medicine availability via catalogue mapping |
| EPIC-001 - Auth & Identity | JWT for admin and pharmacy roles |
| Infrastructure: PostgreSQL full-text search + pg_trgm | Name/salt/brand trigram search index |
| Infrastructure: Redis | Search autocomplete cache, category list cache |
| Infrastructure: CDN | Category icon hosting |
