# STORY-006: COD Float Management (Finance Side)

| Field | Value |
|---|---|
| Story ID | EPIC-012/STORY-006 |
| Epic | EPIC-012 - Payments and Finance |
| Title | COD Float Management (Finance Side) |
| Status | Draft |
| Priority | P1 |
| Estimated Effort | 1 Sprint |
| Last Updated | 2026-07-24 |

---

## Overview

This story covers the platform-level financial view of COD cash management. While EPIC-011/STORY-007 handles the rider-facing and ops-facing COD tracking, this story focuses on the admin finance team's needs: a real-time float summary, a daily reconciliation report, and the ability to trigger the automated reconciliation job. Finance teams use this to ensure COD cash collected by riders is being deposited correctly, identify variance between expected and actual deposits, and maintain accurate records for accounting.

---

## User Roles

| Role | Capability |
|---|---|
| `admin_finance` | View float summary, view reconciliation report, trigger reconciliation |
| `admin_super` | All admin_finance capabilities |
| `admin_operations` | View float summary (read-only) |

---

## Business Rules

| # | Rule |
|---|---|
| BR-001 | **COD float** = total COD cash collected by riders that has not yet been deposited to the platform. |
| BR-002 | **Float risk threshold** = Rs 2,000 per rider (configurable). Riders above this threshold are shown with risk_status = `FLOAT_RISK` in the table. |
| BR-003 | **Daily reconciliation job** runs at 11 PM IST; it matches confirmed deposits against collected amounts and computes any variance. |
| BR-004 | A variance of more than **Rs 100** in the daily reconciliation triggers an alert to admin_finance. |
| BR-005 | Platform revenue from COD orders is settled only after the rider deposits the cash; the settlement cron checks rider `cod_in_hand` before computing net payable. |
| BR-006 | The reconciliation report is required for accounting audit; it must be exportable as CSV. |
| BR-007 | Auto-reconcile marks each `CODDeposit` record with `CONFIRMED` or flags discrepancies for admin review; it does not automatically mark unmatched deposits as rejected. |

---

## API Endpoints

### GET /api/v1/admin/finance/cod-float

**Auth:** `Bearer JWT` (admin_finance, admin_operations, admin_super)  
**Description:** Platform-level COD float summary with rider breakdown.

**Query Params:** `?zone_id=<uuid>&risk_only=true&page=1&limit=20`

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "summary": {
      "total_cod_in_transit": 28450.00,
      "collected_today": 18200.00,
      "deposited_today": 15200.00,
      "float_risk_amount": 8600.00,
      "float_risk_riders_count": 4,
      "float_risk_threshold": 2000.00
    },
    "riders": [
      {
        "rider_id": "rider_uuid",
        "rider_name": "Ravi Kumar",
        "zone_name": "Koramangala",
        "collected": 2850.00,
        "deposited": 1000.00,
        "in_hand": 1850.00,
        "risk_status": "SAFE",
        "last_deposit_at": "2026-07-24T14:00:00Z"
      },
      {
        "rider_id": "rider_uuid_2",
        "rider_name": "Suresh M",
        "zone_name": "Indiranagar",
        "collected": 3600.00,
        "deposited": 0.00,
        "in_hand": 3600.00,
        "risk_status": "FLOAT_RISK",
        "last_deposit_at": null
      }
    ]
  },
  "meta": {
    "page": 1,
    "limit": 20,
    "total": 45
  }
}
```

---

### GET /api/v1/admin/finance/cod-float/reconciliation-report

**Auth:** `Bearer JWT` (admin_finance, admin_super)  
**Description:** Daily COD reconciliation report.

**Query Params:** `?date=2026-07-24` (defaults to previous day if omitted)

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "report_date": "2026-07-24",
    "total_cod_orders": 124,
    "total_cod_amount": 38500.00,
    "collected_by_riders": 38500.00,
    "deposited_to_platform": 35200.00,
    "outstanding_float": 3300.00,
    "variance": 0.00,
    "variance_reason": null,
    "reconciliation_status": "BALANCED",
    "rider_breakdown": [
      {
        "rider_id": "rider_uuid",
        "rider_name": "Ravi Kumar",
        "orders": 14,
        "collected": 4200.00,
        "deposited": 4200.00,
        "variance": 0.00,
        "status": "MATCHED"
      },
      {
        "rider_id": "rider_uuid_3",
        "rider_name": "Arjun K",
        "orders": 8,
        "collected": 2100.00,
        "deposited": 1800.00,
        "variance": 300.00,
        "status": "DISCREPANCY"
      }
    ],
    "generated_at": "2026-07-24T23:05:00Z"
  },
  "meta": {}
}
```

**Errors:**
| Code | HTTP | Meaning |
|---|---|---|
| `REPORT_NOT_GENERATED` | 404 | Reconciliation job has not run for the requested date |
| `INVALID_DATE` | 422 | date is in the future |

---

### POST /api/v1/admin/finance/cod-float/auto-reconcile

**Auth:** `Bearer JWT` (admin_finance, admin_super)  
**Description:** Manually trigger the COD reconciliation job (normally runs at 11 PM automatically).

**Request Body:**
```json
{
  "date": "2026-07-24"
}
```

**Response 200 OK:**
```json
{
  "success": true,
  "data": {
    "job_id": "job_uuid",
    "date": "2026-07-24",
    "status": "RUNNING",
    "triggered_by": "admin_uuid",
    "triggered_at": "2026-07-24T15:00:00Z",
    "estimated_completion_seconds": 30,
    "result_url": "/api/v1/admin/finance/cod-float/reconciliation-report?date=2026-07-24"
  },
  "meta": {}
}
```

**Errors:**
| Code | HTTP | Meaning |
|---|---|---|
| `JOB_ALREADY_RUNNING` | 409 | Reconciliation job for this date is already in progress |
| `INVALID_DATE` | 422 | date is in the future |

---

## Data Models

### CODReconciliationReport

| Field | Type | Nullable | Description |
|---|---|---|---|
| `id` | UUID v4 | No | Primary key |
| `report_date` | DATE | No | Date the report covers |
| `total_cod_orders` | INTEGER | No | COD orders in the day |
| `total_cod_amount` | DECIMAL(14,2) | No | Total COD collected |
| `collected_by_riders` | DECIMAL(14,2) | No | Sum of CODCollection amounts |
| `deposited_to_platform` | DECIMAL(14,2) | No | Sum of confirmed deposits |
| `outstanding_float` | DECIMAL(14,2) | No | `collected ? deposited` |
| `variance` | DECIMAL(12,2) | No | Unexplained discrepancy |
| `variance_reason` | TEXT | Yes | Admin-entered explanation |
| `reconciliation_status` | ENUM(`BALANCED`,`DISCREPANCY`,`PENDING`) | No | Overall status |
| `alert_sent` | BOOLEAN | No | Whether variance alert was dispatched |
| `generated_at` | TIMESTAMPTZ | No | Report generation timestamp |
| `triggered_by` | UUID | Yes | Admin who triggered manually (null = cron) |

---

## Acceptance Criteria

| # | Criterion |
|---|---|
| AC-001 | `GET /admin/finance/cod-float` displays accurate real-time `total_cod_in_transit` as the sum of all riders' `cod_in_hand`. |
| AC-002 | Riders with `in_hand > Rs 2,000` appear with `risk_status = FLOAT_RISK`; `risk_only=true` filter shows only those riders. |
| AC-003 | The daily reconciliation job runs at 11 PM IST; the next morning `GET /admin/finance/cod-float/reconciliation-report?date=yesterday` returns the report. |
| AC-004 | A variance > Rs 100 in the report triggers an alert to admin_finance; the report shows `variance_reason` field for admin to record their explanation. |
| AC-005 | `POST /auto-reconcile` for a date in the future returns HTTP 422 `INVALID_DATE`. |
| AC-006 | `POST /auto-reconcile` while a job is already running for the same date returns HTTP 409 `JOB_ALREADY_RUNNING`. |
| AC-007 | Reconciliation report is exportable as CSV (via the standard `/export` pattern or download link); the CSV includes rider-level breakdown. |

---

## Dependencies

| Dependency | Type | Notes |
|---|---|---|
| COD Reconciliation (EPIC-011/STORY-007) | Internal | CODCollection and CODDeposit source data |
| RiderProfile (EPIC-011/STORY-001) | Internal | `cod_in_hand` counter |
| Scheduled Job Runner | Internal | 11 PM auto-reconcile cron |
| Notification Service (EPIC-013) | Internal | Variance alert email to admin_finance |

---

## Notes

- The reconciliation report is sourced from `CODCollection` (deliveries where `is_deposited = false`) and `CODDeposit` (status = CONFIRMED) tables.
- Discrepancy riders are flagged for follow-up; the reconciliation job does not automatically modify any financial records - it is a reporting job only.
