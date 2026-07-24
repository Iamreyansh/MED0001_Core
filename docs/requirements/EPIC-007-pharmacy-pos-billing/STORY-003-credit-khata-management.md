# STORY-003: Credit / Khata Management - Customer Credit Tracking

| Attribute | Value |
|-----------|-------|
| **Story ID** | STORY-003 |
| **Epic** | EPIC-007 - Pharmacy POS & Billing |
| **Priority** | P1 |
| **Complexity** | L |
| **Status** | Draft |

---

## Overview

This story provides the Khata (credit ledger) module for Starter-plan and above pharmacies. When a customer purchases on credit at the POS, the outstanding amount is tracked per customer in the Khata. Pharmacists can view the aging summary, send polite or firm payment reminders via WhatsApp or SMS, and record repayments. The module surfaces collection KPIs, aging analysis, and a complete ledger per customer - enabling small pharmacies to manage credit relationships the way they would in a traditional ledger book, but digitally.

---

## User Roles & Access

| Role | Access Level | Description |
|------|-------------|-------------|
| `pharmacy_owner` | Full read + write | View all Khata, record repayments, send reminders |
| `pharmacy_staff` | Read + record repayment | View Khata and record repayments; cannot send reminders |
| `admin_finance` | Read-only | Cross-pharmacy credit/collections data |
| `admin_support` | Read-only | Support lookup for customer disputes |
| `customer` | No access | Not applicable |

---

## Business Rules

1. **Starter plan gating.** All Khata endpoints return 403 `PLAN_FEATURE_LOCKED` for Free-plan pharmacies.
2. **CREDIT POS payment creates Khata entry.** When `payment_method = CREDIT` at POS checkout, a `KhataEntry` of type `DEBIT` is created with the invoice amount. The customer must be a named customer (not walk-in).
3. **Repayment creates CREDIT entry.** `POST /khata/:customer_id/repayment` creates a `KhataEntry` of type `CREDIT`, reducing the outstanding balance. The system auto-generates a repayment receipt with a receipt number.
4. **Outstanding balance** = sum of all DEBIT entries - sum of all CREDIT (repayment) entries for that customer-pharmacy pair.
5. **Maximum credit limit.** Each pharmacy can set a default max credit limit (default Rs 50,000). If `outstanding_balance + new_purchase > credit_limit`, the POS checkout rejects the CREDIT payment with `CREDIT_LIMIT_EXCEEDED`. The limit is configurable per customer.
6. **Overdue definition.** A bill is overdue if it has been unpaid (not fully offset by repayments) for more than 30 days from the invoice date.
7. **Aging buckets.** Khata aging is segmented as: 0-30 days (Current), 31-60 days (Overdue 1), 60+ days (Overdue 2). Amounts are allocated to buckets by bill age, not repayment date.
8. **WhatsApp reminder template.** Reminders use pre-approved WhatsApp Business templates. POLITE template sends a gentle reminder; FIRM template includes an outstanding amount and urgency phrasing. If WhatsApp fails, SMS is the automatic fallback.
9. **Repayment receipt.** Each repayment generates a receipt number in format `RCPT-{YYYY}-{MM}-{NNNNNN}` (same numbering convention as invoices). The receipt is sharable via WhatsApp/SMS.
10. **Walk-in customers are ineligible for credit.** Only customers with a `customer_id` (registered or created at POS with phone number) can receive credit.

---

## API Endpoints

### 1. Khata Outstanding List

```
GET /api/v1/pharmacy/khata
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 30 req/min
**Plan:** Starter+

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `overdue_only` | boolean | `false` | Show only customers with overdue bills |
| `sort` | enum | `outstanding_desc` | `outstanding_desc \| outstanding_asc \| oldest_bill` |
| `q` | string | - | Search by customer name or phone |
| `page` | integer | `1` | Page |
| `limit` | integer | `20` | Items per page |

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "kpi": {
      "total_outstanding": 285000.00,
      "overdue_30d": 62000.00,
      "collected_this_month": 48000.00,
      "collection_rate_pct": 74.5,
      "all_time_credit_given": 1240000.00
    },
    "aging_chart": {
      "current_0_30d": 223000.00,
      "overdue_31_60d": 40000.00,
      "overdue_60d_plus": 22000.00
    },
    "customers": [
      {
        "customer_id": "uuid",
        "name": "Ramesh Gupta",
        "phone": "+919876543001",
        "outstanding": 8500.00,
        "oldest_unpaid_date": "2026-06-15",
        "days_overdue": 39,
        "is_overdue": true
      }
    ]
  },
  "meta": { "page": 1, "limit": 20, "total": 48 }
}
```

---

### 2. Customer Credit Detail (Ledger)

```
GET /api/v1/pharmacy/khata/:customer_id
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 60 req/min
**Plan:** Starter+

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "customer": {
      "customer_id": "uuid",
      "name": "Ramesh Gupta",
      "phone": "+919876543001",
      "credit_limit": 50000.00
    },
    "summary": {
      "total_outstanding": 8500.00,
      "overdue_amount": 3000.00,
      "oldest_unpaid_days": 39,
      "credit_utilisation_pct": 17.0
    },
    "unpaid_bills": [
      {
        "invoice_id": "uuid",
        "invoice_number": "INV-2026-06-000287",
        "invoice_date": "2026-06-15",
        "amount": 3000.00,
        "days_since": 39
      }
    ],
    "ledger": [
      {
        "entry_id": "uuid",
        "type": "DEBIT",
        "date": "2026-07-10",
        "reference": "INV-2026-07-000390",
        "amount": 5500.00,
        "running_balance": 8500.00
      },
      {
        "entry_id": "uuid",
        "type": "CREDIT",
        "date": "2026-07-05",
        "reference": "RCPT-2026-07-000012",
        "amount": 2000.00,
        "running_balance": 3000.00
      }
    ],
    "total_outstanding": 8500.00
  }
}
```

---

### 3. Record Repayment

```
POST /api/v1/pharmacy/khata/:customer_id/repayment
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 30 req/min
**Plan:** Starter+

**Request Body (application/json):**

```json
{
  "amount": "number > 0 - required",
  "payment_mode": "CASH | UPI | CARD - required",
  "note": "string max 300 - optional",
  "reference_number": "string max 50 - optional"
}
```

**Success Response - 201 Created:**

```json
{
  "success": true,
  "data": {
    "receipt_id": "uuid",
    "receipt_number": "RCPT-2026-07-000013",
    "customer_name": "Ramesh Gupta",
    "amount": 5000.00,
    "payment_mode": "CASH",
    "previous_outstanding": 8500.00,
    "new_outstanding": 3500.00,
    "receipt_pdf_url": "https://cdn.medmate.in/pharmacy/uuid/RCPT-2026-07-000013.pdf",
    "created_at": "2026-07-24T13:00:00Z"
  }
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `REPAYMENT_EXCEEDS_OUTSTANDING` | `amount > total_outstanding` |
| 404 | `CUSTOMER_NOT_FOUND` | Customer ID not found for this pharmacy |

---

### 4. Send Payment Reminder

```
POST /api/v1/pharmacy/khata/:customer_id/remind
```

**Authentication:** Bearer JWT - `pharmacy_owner`
**Rate Limit:** 10 req/min per customer
**Plan:** Starter+

**Request Body (application/json):**

```json
{
  "channel": "WHATSAPP | SMS - required",
  "message_template": "POLITE | FIRM - required"
}
```

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "channel": "WHATSAPP",
    "template": "POLITE",
    "sent_to": "+919876543001",
    "outstanding_amount": 8500.00,
    "message_id": "wa_msg_uuid",
    "sent_at": "2026-07-24T13:05:00Z"
  }
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `NO_OUTSTANDING_BALANCE` | Customer has no outstanding balance |
| 403 | `STAFF_CANNOT_REMIND` | Only `pharmacy_owner` can send reminders |
| 429 | `REMINDER_RATE_LIMITED` | Reminder already sent to this customer in the last 24 hours |
| 503 | `CHANNEL_UNAVAILABLE` | Messaging service unavailable; SMS fallback triggered automatically |

---

### 5. Payment History (All Repayments)

```
GET /api/v1/pharmacy/khata/payment-history
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 30 req/min
**Plan:** Starter+

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `from_date` | date | - | Date range start |
| `to_date` | date | - | Date range end |
| `payment_mode` | enum | - | `CASH \| UPI \| CARD` |
| `q` | string | - | Search by customer name, receipt number |
| `page` | integer | `1` | Page |
| `limit` | integer | `20` | Items per page |
| `export` | enum | - | `EXCEL` |

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "repayments": [
      {
        "receipt_id": "uuid",
        "receipt_number": "RCPT-2026-07-000013",
        "date": "2026-07-24",
        "customer_name": "Ramesh Gupta",
        "customer_phone": "+919876543001",
        "mode": "CASH",
        "amount": 5000.00,
        "note": "Received in cash",
        "running_outstanding_after": 3500.00
      }
    ],
    "period_total_collected": 48000.00
  },
  "meta": { "page": 1, "limit": 20, "total": 62 }
}
```

---

## Data Models

### KhataEntry

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID | PK | Unique ledger entry |
| `pharmacy_id` | UUID | FK ? Pharmacy, NOT NULL | Owning pharmacy |
| `customer_id` | UUID | FK ? Customer, NOT NULL | Customer (named only) |
| `type` | ENUM | NOT NULL | DEBIT (credit given) / CREDIT (repayment) |
| `amount` | NUMERIC(12,2) | > 0, NOT NULL | Transaction amount |
| `invoice_id` | UUID | FK ? Invoice, nullable | Source invoice (for DEBIT entries) |
| `repayment_id` | UUID | FK ? KhataRepayment, nullable | Source repayment (for CREDIT entries) |
| `reference_number` | VARCHAR(50) | NOT NULL | Invoice number or receipt number |
| `notes` | TEXT | nullable | Free-text note |
| `running_balance` | NUMERIC(12,2) | NOT NULL | Outstanding after this entry |
| `created_at` | TIMESTAMPTZ | NOT NULL | Entry creation timestamp |

### KhataRepayment

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID | PK | Unique repayment ID |
| `pharmacy_id` | UUID | FK ? Pharmacy, NOT NULL | Owning pharmacy |
| `customer_id` | UUID | FK ? Customer, NOT NULL | Paying customer |
| `receipt_number` | VARCHAR(30) | NOT NULL, UNIQUE per pharmacy | Generated receipt number |
| `amount` | NUMERIC(12,2) | > 0, NOT NULL | Amount collected |
| `payment_mode` | ENUM | NOT NULL | CASH / UPI / CARD |
| `reference_number` | VARCHAR(50) | nullable | UPI transaction ref |
| `notes` | TEXT | nullable | Staff note |
| `collected_by` | UUID | FK ? Staff, NOT NULL | Staff member who collected |
| `created_at` | TIMESTAMPTZ | NOT NULL | Collection timestamp |

---

## Acceptance Criteria

- [ ] Given a Free-plan pharmacy JWT, when `GET /api/v1/pharmacy/khata` is called, then a 403 `PLAN_FEATURE_LOCKED` error is returned.
- [ ] Given a POS checkout with `payment_method = CREDIT` for a named customer, then a `KhataEntry` of type `DEBIT` is created with the invoice amount.
- [ ] Given `POST /khata/:customer_id/repayment` with `amount = 5000`, then a receipt number in `RCPT-YYYY-MM-NNNNNN` format is returned and `outstanding` decreases by 5000.
- [ ] Given a repayment amount greater than the customer's outstanding balance, then a 400 `REPAYMENT_EXCEEDS_OUTSTANDING` error is returned.
- [ ] Given `GET /khata` with `overdue_only = true`, then only customers with at least one bill older than 30 days and still unpaid are returned.
- [ ] Given `GET /khata/:customer_id`, then the `ledger` array is in reverse-chronological order with a correct `running_balance` column.
- [ ] Given `POST /khata/:customer_id/remind` called twice within 24 hours, then the second call returns 429 `REMINDER_RATE_LIMITED`.
- [ ] Given a POS checkout for a customer whose `outstanding + new_purchase > credit_limit`, then a 400 `CREDIT_LIMIT_EXCEEDED` error is returned at checkout.

---

## Dependencies

- **EPIC-007 / STORY-001 (POS):** CREDIT payment at checkout triggers Khata entry creation.
- **EPIC-010 (Notifications):** WhatsApp/SMS reminder dispatch.
- **Plan Gating Middleware:** All endpoints validate Starter+ plan.

---

## Notes

- `running_balance` on `KhataEntry` is denormalized for display performance and must be recomputed on any historical correction (which is not supported in v1 - ledger is append-only).
- The `collection_rate_pct` KPI is computed as `(collected_this_month / credit_given_this_month) - 100`.
- Aging chart data in the KPI is computed at query time using `CURRENT_DATE - invoice_date` for each unpaid bill and bucketing into the three ranges.
