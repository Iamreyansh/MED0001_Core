# EPIC-013: Marketing and Growth

| Field | Value |
|---|---|
| Epic ID | EPIC-013 |
| Epic Name | Marketing and Growth |
| Folder | `docs/requirements/EPIC-013-marketing-growth/` |
| Status | In Progress |
| Owner | Product - Growth Team |
| Created | 2026-07-24 |
| Last Updated | 2026-07-24 |

---

## Overview

EPIC-013 covers all platform capabilities that drive customer acquisition, retention, and revenue growth for the Namma MedMate marketplace. It encompasses promotional mechanics (coupons and banners), omnichannel marketing campaigns, customer segmentation, a referral program, and a loyalty points system. Together these features form the growth loop: attract new customers via referrals and campaigns, retain them through loyalty rewards, and re-engage dormant users through targeted, segment-driven promotions. Every feature is instrumented for analytics - tracking spend, redemptions, ROI, and customer lifetime value - so that the Admin HQ team can measure and optimise growth spend with precision.

---

## Stories

| Story ID | Title | Status | Priority |
|---|---|---|---|
| STORY-001 | Coupon Management | Planned | P0 |
| STORY-002 | Banner CMS Management | Planned | P1 |
| STORY-003 | Campaign Management | Planned | P1 |
| STORY-004 | Customer Segmentation | Planned | P1 |
| STORY-005 | Referral Program | Planned | P0 |
| STORY-006 | Loyalty Program | Planned | P0 |

---

## Roles Involved

| Role | Access |
|---|---|
| `admin_super` | Full read/write on all marketing features; update program settings |
| `admin_operations` | Create/edit coupons, banners, campaigns, segments |
| `admin_finance` | Read analytics, budget tracking |
| `customer` | Validate/apply coupons; view available offers; referral & loyalty |
| `pharmacy_owner` | No access (marketing is platform-funded) |

---

## Key Business Rules (Epic-Level)

1. All discount funding is platform-side unless explicitly contracted otherwise with pharmacy.
2. Budget exhaustion on any coupon auto-pauses it and triggers admin notification.
3. Campaign attribution window is 48 hours from last interaction.
4. Loyalty points are earned only after order delivery (not on placement or payment).
5. Referral rewards require the referee's first order to reach DELIVERED status.
6. All marketing actions are audit-logged (who created, modified, or paused).

---

## Dependencies

| Dependency | Epic / Module |
|---|---|
| Customer Wallet | EPIC-008 Finance |
| Order Lifecycle | EPIC-004 Orders |
| Customer Auth & Profiles | EPIC-002 Auth |
| Notification Engine | EPIC-010 Notifications |
| Analytics / Reporting | EPIC-016 Analytics |
| Segment Compute Job | Background Jobs / Worker |

---

## Metrics & KPIs

| Metric | Description |
|---|---|
| Discount Spend (Rs) | Total platform-funded discount disbursed via coupons |
| ROAS | Revenue Attributed / Discount Spend |
| Referral CAC | Total referral rewards paid / converted referrals |
| Loyalty Liability (Rs) | Total outstanding redeemable points - Rs 1 |
| Campaign ROI | (Revenue Attributed ? Campaign Cost) / Campaign Cost - 100 |
| Banner CTR | Clicks / Impressions |
