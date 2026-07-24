# STORY-009: Financial Overview Dashboard

| Field | Value |
|---|---|
| Story ID | EPIC-012/STORY-009 |
| Epic | EPIC-012 - Payments and Finance |
| Title | Financial Overview Dashboard |
| Status | Draft |
| Priority | P1 |
| Estimated Effort | 1 Sprint |
| Last Updated | 2026-07-24 |

---

## Overview

This story provides the real-time P&L and operational finance overview for the Admin HQ finance module. It aggregates data from payments, settlements, payouts, refunds, and the wallet into a set of read-only KPI endpoints. The dashboard exposes: real-time KPI chips, a P&L summary for a selected period, the platform's current cash position (what's been collected vs. disbursed), and key financial ratios (take rate, payout ratio, refund rate, COD share). All data is computed from live sources; no mutations occur through these endpoints.

---

## User Roles

| Role | Capability |
|---|---|
| `admin_finance` | View all finance overview endpoints |
| `admin_super` | All admin_finance capabilities |

---

## Business Rules

| # | Rule |
|---|---|
| BR-001 | All finance overview endpoints are **read-only**; no data mutations are permitted. |
| BR-002 | KPI chips (`GET /kpi`) are computed from **live data** (real-time aggregates); they are not cached longer than 60 seconds. |
| BR-003 | P&L (`GET /pnl`) is computed for the selected period: `net_revenue = commission_earned ? refunds_issued ? gateway_fees`. |
| BR-004 | Cash position (`GET /cash-position`) is cumulative: `platform_net = received_from_customers ? paid_to_pharmacies ? paid_to_riders ? refunded ? held_in_wallet`. |
| BR-005 | **Period selector** supports: `TODAY`, `7D`, `30D`, `90D`, `CUSTOM` (requires `from_date` and `to_date`). |
| BR-006 | **Take rate** = `(commission_earned / GMV) - 100`. **Payout ratio** = `(total_payouts / GMV) - 100`. **Refund rate** = `(total_refunds / GMV) - 100`. **COD share** = `(COD_orders / total_orders) - 100`. |
| BR-007 | The 7-day GMV chart returns hourly data for `TODAY` or `7D` period, and daily data for `30D` or `90D` periods. |
| BR-008 | Only `admin_finance` and `admin_super` have access to all finance overview endpoints. |

---

## API Endpoints

### GET /api/v1/admin/finance/kpi

**Auth:** `Bearer JWT` (admin_finance, admin_super)  
**Description:** Real-time KPI chips for the finance dashboard header.

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "as_of": "2026-07-24T16:00:00Z",
    "gmv_today": 185000.00,
    "platform_revenue_today": 14800.00,
    "pharmacy_payout_due": 420500.00,
    "rider_payout_due": 72400.00,
    "refunds_pending": 12,
    "refunds_pending_value": 8450.00,
    "cod_in_hand": 28450.00,
    "active_wallet_balance_total": 1250000.00,
    "gateway_fees_today": 2736.00
  },
  "meta": {}
}
```

---

### GET /api/v1/admin/finance/pnl

**Auth:** `Bearer JWT` (admin_finance, admin_super)  
**Description:** P&L summary for a selected period.

**Query Params:** `?period=7D` OR `?period=CUSTOM&from=2026-07-01&to=2026-07-24`

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "period": "7D",
    "from": "2026-07-17",
    "to": "2026-07-24",
    "gmv": 1295000.00,
    "commission_earned": 103600.00,
    "refunds_issued": 28500.00,
    "gateway_fees": 19175.00,
    "net_revenue": 55925.00,
    "orders_count": 3714,
    "avg_order_value": 348.60,
    "gmv_chart": [
      { "label": "2026-07-17", "gmv": 162000.00, "orders": 465 },
      { "label": "2026-07-18", "gmv": 178000.00, "orders": 511 },
      { "label": "2026-07-19", "gmv": 155000.00, "orders": 444 },
      { "label": "2026-07-20", "gmv": 198000.00, "orders": 568 },
      { "label": "2026-07-21", "gmv": 215000.00, "orders": 617 },
      { "label": "2026-07-22", "gmv": 202000.00, "orders": 579 },
      { "label": "2026-07-23", "gmv": 185000.00, "orders": 530 }
    ],
    "gmv_breakdown_pie": {
      "pharmacy_payout": 946200.00,
      "platform_commission": 103600.00,
      "tcs_collected": 12950.00,
      "gateway_fees": 19175.00,
      "refunds": 28500.00,
      "net_platform_revenue": 55925.00
    }
  },
  "meta": {}
}
```

**Errors:**
| Code | HTTP | Meaning |
|---|---|---|
| `INVALID_PERIOD` | 422 | period not in allowed enum |
| `CUSTOM_DATES_REQUIRED` | 422 | period=CUSTOM but from/to missing |
| `DATE_RANGE_TOO_LARGE` | 422 | CUSTOM range > 365 days |

---

### GET /api/v1/admin/finance/cash-position

**Auth:** `Bearer JWT` (admin_finance, admin_super)  
**Description:** Cumulative cash position - what the platform has received, disbursed, and holds.

**Query Params:** `?period=30D`

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "period": "30D",
    "from": "2026-06-24",
    "to": "2026-07-24",
    "received_from_customers": 5550000.00,
    "paid_to_pharmacies": 4100000.00,
    "paid_to_riders": 320000.00,
    "refunded_to_customers": 122000.00,
    "held_in_wallet": 1250000.00,
    "cod_in_transit": 28450.00,
    "platform_net": 1008000.00,
    "gateway_fees_paid": 82000.00,
    "tcs_collected_held": 55500.00,
    "net_free_cash": 869950.00
  },
  "meta": {}
}
```

---

### GET /api/v1/admin/finance/ratios

**Auth:** `Bearer JWT` (admin_finance, admin_super)  
**Description:** Key financial health ratios for the selected period.

**Query Params:** `?period=30D`

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "period": "30D",
    "from": "2026-06-24",
    "to": "2026-07-24",
    "take_rate_pct": 8.0,
    "payout_ratio_pct": 79.3,
    "refund_rate_pct": 2.2,
    "cod_share_pct": 38.5,
    "gateway_fee_rate_pct": 1.48,
    "net_revenue_margin_pct": 10.1,
    "weekly_gmv_trend": "UP",
    "weekly_gmv_change_pct": 4.7
  },
  "meta": {}
}
```

---

## Data Models

*No new data models are introduced in this story. All data is aggregated from existing models:*

| Source Model | Data Used |
|---|---|
| `Payment` | GMV, gateway fees, wallet portions |
| `PharmacySettlement` | Commission, TCS, payout amounts |
| `RiderPayout` | Rider payout amounts |
| `Refund` | Refund amounts and statuses |
| `WalletAccount` | Total active wallet balance |
| `FinancialLedger` | All money movement aggregates |
| `Order` | Order counts, COD share |
| `CODCollection` | COD in transit |

---

## Acceptance Criteria

| # | Criterion |
|---|---|
| AC-001 | `GET /admin/finance/kpi` returns current-day `gmv_today` matching the sum of `Payment.amount` where `status = CAPTURED` and `created_at` is today. |
| AC-002 | `GET /admin/finance/pnl?period=7D` correctly computes `net_revenue = commission_earned ? refunds_issued ? gateway_fees` for the past 7 days. |
| AC-003 | `gmv_chart` in the P&L response returns daily data points for `7D`, `30D`, `90D` periods, and hourly data for `TODAY`. |
| AC-004 | `GET /admin/finance/cash-position` shows `platform_net = received_from_customers ? paid_to_pharmacies ? paid_to_riders ? refunded_to_customers`. |
| AC-005 | `get /admin/finance/ratios` computes `take_rate_pct = (commission / GMV) - 100` correctly for the selected period. |
| AC-006 | `period=CUSTOM` without `from` and `to` returns HTTP 422 `CUSTOM_DATES_REQUIRED`. |
| AC-007 | Any role other than `admin_finance` or `admin_super` receives HTTP 403 on all finance overview endpoints. |
| AC-008 | KPI chips cached for no more than 60 seconds; subsequent requests within 60 seconds return cached data with `as_of` timestamp indicating cache age. |

---

## Dependencies

| Dependency | Type | Notes |
|---|---|---|
| Payment Processing (EPIC-012/STORY-001) | Internal | GMV, gateway fees |
| Pharmacy Settlements (EPIC-012/STORY-003) | Internal | Commission, TCS, payout amounts |
| Rider Payouts (EPIC-012/STORY-004) | Internal | Rider payout totals |
| Refund Processing (EPIC-012/STORY-005) | Internal | Refund amounts |
| Wallet Operations (EPIC-012/STORY-002) | Internal | Total wallet balance held |
| Financial Ledger (EPIC-012/STORY-008) | Internal | Authoritative source for all aggregates |
| Order Management (EPIC-010) | Internal | Order counts, COD/online split |
| Redis | External | KPI chip caching (60-second TTL) |

---

## Notes

- The `gmv_breakdown_pie` data in the P&L response provides a "where does the GMV go" breakdown for the admin finance team's operational review; it is the primary visualization on the finance dashboard home screen.
- `net_free_cash = platform_net ? tcs_collected_held ? gateway_fees_paid`; TCS collected is not the platform's free cash as it must be remitted to the government.
- All amounts in responses are in Indian Rupees (DECIMAL with 2 decimal places); no currency conversion is required.
- The ratios endpoint's `weekly_gmv_trend` is computed by comparing the current week's daily average GMV against the previous week's; `UP`, `DOWN`, or `FLAT` (< 1% change).
