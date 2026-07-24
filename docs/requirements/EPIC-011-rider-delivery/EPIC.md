# EPIC-011: Rider Management and Delivery

| Field | Value |
|---|---|
| Epic ID | EPIC-011 |
| Epic Name | Rider Management and Delivery |
| Product | Namma MedMate |
| Domain | Delivery Operations |
| Status | In Progress |
| Priority | P0 - Core Platform |
| Owner | Engineering Lead - Delivery |
| Last Updated | 2026-07-24 |

---

## Overview

EPIC-011 covers the end-to-end lifecycle of delivery riders on the Namma MedMate platform - from onboarding and KYC verification through real-time GPS tracking, dynamic order assignment, zone-based fleet management, delivery fee pricing, COD cash reconciliation, and rider performance/earnings management. This epic is foundational to the platform's hyperlocal 12-30 minute delivery promise. It encompasses both the rider-facing mobile app flows and the Admin HQ operations dashboard that allows the operations team to manage the entire delivery fleet in real time. All delivery financial flows (COD collection, rider payouts) that originate here are settled through EPIC-012.

---

## Goals

- Ensure every order placed by a customer is assigned to a verified, available rider within 5 minutes.
- Provide real-time GPS visibility of riders to both customers (for their active order) and operations admins.
- Enforce strict COD float limits to minimise cash risk in the field.
- Give admins granular control over zones, pricing, surge toggles, and fleet rebalancing.
- Track rider performance to power fair, transparent incentive calculations.

---

## Stories

| Story ID | Title | Status | Priority | Est. Sprints |
|---|---|---|---|---|
| EPIC-011/STORY-001 | Rider Onboarding & KYC | Draft | P0 | 2 |
| EPIC-011/STORY-002 | Rider Availability & Shift Management | Draft | P0 | 1 |
| EPIC-011/STORY-003 | Order Assignment Engine | Draft | P0 | 2 |
| EPIC-011/STORY-004 | Real-Time Rider GPS Tracking | Draft | P0 | 2 |
| EPIC-011/STORY-005 | Delivery Zone Management | Draft | P1 | 2 |
| EPIC-011/STORY-006 | Delivery Fee Pricing | Draft | P1 | 1 |
| EPIC-011/STORY-007 | COD Reconciliation (Rider Side) | Draft | P1 | 1 |
| EPIC-011/STORY-008 | Rider Incentives & Performance | Draft | P1 | 2 |

**Total Stories:** 8

---

## Roles Involved

| Role | Involvement |
|---|---|
| `rider` | Self-onboarding, KYC upload, status toggling, order accept/pickup/deliver, location posting, COD deposit request, earnings view |
| `admin_operations` | Fleet monitoring, order dispatch, zone management, rider status override, rebalancing |
| `admin_finance` | COD reconciliation, rider payout release |
| `admin_super` | All admin operations + config changes |
| `customer` | Rider location view (active order only), delivery fee estimate |

---

## Key Data Entities

| Entity | Description |
|---|---|
| `RiderProfile` | Core rider record: identity, vehicle, zone, status, KYC state, wallet |
| `RiderKYCDocument` | Uploaded KYC document with type, expiry, verification status |
| `RiderLocation` | Time-series GPS points; Redis for live, PostgreSQL for history |
| `DeliveryZone` | GeoJSON polygon with pricing config, SLA, surge state |
| `DeliveryGeofence` | Zone boundary used for breach detection |
| `RiderShift` | Shift session (online-start to offline-end) for incentive calculation |
| `OrderAssignment` | Order ? rider linking record with assignment type and timestamps |
| `CODCollection` | Cash collected per delivery; deposit tracking |
| `RiderEarningsLedger` | Per-day earnings breakdown: base, incentives, tips, deductions |
| `RiderPayout` | Weekly payout record with status |

---

## External Dependencies

| Dependency | Used For |
|---|---|
| Google Maps Distance Matrix API | ETA calculation on location update |
| Google Maps Geocoding API | Address to lat/lng, zone matching |
| Redis | Live rider location store, assignment queue |
| WebSocket / SSE server | Real-time location push to customer app |
| Razorpay Route | Rider payout disbursement |
| SMS Gateway (MSG91 / Exotel) | OTP, alerts, payout notifications |
| EPIC-010 (Order Management) | Orders feed into assignment queue |
| EPIC-012 (Payments & Finance) | Rider payout, COD settlement |
| EPIC-013 (Notifications) | Rider push notifications |

---

## Non-Functional Requirements

| Requirement | Target |
|---|---|
| Location update ingestion | < 100 ms p99 |
| Order assignment (auto) | < 10 seconds end-to-end |
| Live fleet dashboard refresh | 30-second polling or WebSocket |
| Location history retention | 30 days |
| KYC document storage | AWS S3 with private ACL |
| GPS accuracy threshold | Accept only if `accuracy` ? 50 m |

---

## Out of Scope (EPIC-011)

- Rider mobile app UI/UX implementation (frontend)
- Surge pricing triggers based on weather/events (handled by Automation Engine, EPIC-015)
- Rider ratings and review content moderation (EPIC-016)
- Inter-city or scheduled delivery modes
