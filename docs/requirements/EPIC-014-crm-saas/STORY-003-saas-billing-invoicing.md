# STORY-003: SaaS Billing and Invoicing

| Field | Value |
|---|---|
| Story ID | EPIC-014-STORY-003 |
| Epic | EPIC-014 CRM SaaS |
| Title | SaaS Billing and Invoicing |
| Priority | P0 |
| Status | Planned |
| Created | 2026-07-24 |
| Last Updated | 2026-07-24 |

---

## Overview

SaaS Billing and Invoicing handles the complete invoice lifecycle for Namma MedMate's Pharmacy ERP subscription business. Invoices are generated automatically on subscription start and on each renewal date, include GST at 18% (SAC code 9983 - software services), and cover the base plan plus any attached add-ons. Admin HQ views a consolidated billing dashboard with KPI chips (collected, due, overdue, collection rate, DSO, dunning count), and can send payment reminders or manually mark invoices as paid. Pharmacy owners can view and download their own invoices and initiate payment. A dunning automation drives the reminders sequence from Day 0 (invoice due) to account suspension at Day 14.

---

## User Roles

| Role | Capability |
|---|---|
| `admin_super` | Full access; manually mark paid; waive invoices |
| `admin_finance` | View all invoices; send reminders; mark paid; download reports |
| `admin_operations` | View invoices; send reminders |
| `pharmacy_owner` | View own invoices; download PDF; initiate payment |

---

## Business Rules

1. **GST inclusion** - all SaaS invoices include GST at 18% on the subtotal (base plan + add-ons). SAC code 9983 (software services) is printed on every invoice.
2. **Invoice generation timing** - invoice generated on subscription start (prorated if mid-cycle) and on each renewal date (full cycle amount).
3. **Dunning sequence** - automated reminders: Day 0 (invoice due) ? Day 3 (first reminder via email + WhatsApp) ? Day 7 (second reminder) ? Day 10 (final warning) ? Day 14 (account suspended / EXPIRED).
4. **DSO calculation** - DSO (Days Sales Outstanding) = average of `(paid_at ? due_date)` in calendar days across all invoices paid in the period; only applies to PAID invoices.
5. **Collection rate** - `collection_rate_pct = (count_paid / (count_paid + count_overdue)) - 100` computed for the selected period.
6. **Overdue definition** - an invoice is OVERDUE when `due_date < now` and status is not PAID or WAIVED; grace period of 7 days before account suspension begins.
7. **Manual payment marking** - admin can mark an invoice PAID with `payment_date`, `payment_mode`, and `reference_number`; used for NEFT/RTGS or cheque payments outside the online gateway.
8. **Invoice immutability** - once generated, invoice line items (plan, add-ons, amounts) cannot be edited; only status and payment metadata can change.
9. **WAIVED status** - admin can waive an invoice (e.g. goodwill gesture or data migration); waived invoices do not count in overdue or collection metrics.
10. **Line items** - each invoice includes: plan subscription line, individual add-on lines (if any), subtotal, GST amount (18%), and total amount due.

---

## API Endpoints

### 1. List Invoices (Admin)

```
GET /api/v1/admin/crm/invoices
Authorization: Bearer JWT (admin_super | admin_finance | admin_operations)
```

**Query Parameters**

| Parameter | Type | Description |
|---|---|---|
| `status` | string | `PAID`, `DUE`, `OVERDUE`, `DUNNING`, `WAIVED` |
| `plan` | string | Filter by plan name |
| `account_id` | UUID | Filter by account |
| `from` | date | Billing period start |
| `to` | date | Billing period end |
| `page` | integer | Default 1 |
| `limit` | integer | Default 20 |

**Response 200**
```json
{
  "success": true,
  "data": {
    "chips": {
      "collected_rs": 428640,
      "due_rs": 62340,
      "overdue_rs": 18900,
      "collection_rate_pct": 87.4,
      "dso_days": 4.2,
      "in_dunning_count": 28
    },
    "collected_by_plan": [
      { "plan": "STARTER", "collected_rs": 201000 },
      { "plan": "RETAIL_PRO", "collected_rs": 227640 }
    ],
    "invoices": [
      {
        "id": "inv_uuid_001",
        "account_id": "acc_uuid_001",
        "pharmacy_name": "Apollo Pharmacy HSR",
        "plan": "STARTER",
        "amount_incl_gst_rs": 824,
        "billing_period": "2026-07-01 to 2026-07-31",
        "status": "PAID",
        "due_date": "2026-07-01",
        "paid_at": "2026-07-03T10:00:00Z"
      }
    ]
  },
  "meta": { "page": 1, "limit": 20, "total": 612 }
}
```

---

### 2. Get Invoice Detail (Admin)

```
GET /api/v1/admin/crm/invoices/:id
Authorization: Bearer JWT (admin_super | admin_finance | admin_operations)
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "id": "inv_uuid_001",
    "invoice_number": "NMM-INV-2026-07-001234",
    "account_id": "acc_uuid_001",
    "pharmacy_name": "Apollo Pharmacy HSR",
    "billing_address": "123 HSR Layout, Bangalore - 560102",
    "gstin": "29ABCDE1234F1Z5",
    "billing_period": { "from": "2026-07-01", "to": "2026-07-31" },
    "line_items": [
      { "description": "STARTER Plan - Monthly", "amount_rs": 699, "sac_code": "9983" },
      { "description": "E-Invoice Integration Add-on", "amount_rs": 199, "sac_code": "9983" }
    ],
    "subtotal_rs": 898,
    "gst_rate_pct": 18,
    "gst_amount_rs": 161,
    "total_amount_rs": 1059,
    "status": "PAID",
    "due_date": "2026-07-01",
    "paid_at": "2026-07-03T10:00:00Z",
    "payment_mode": "UPI",
    "reference_number": "UPI-TXN-98765432",
    "next_cycle_addons": [
      { "name": "E_INVOICE", "amount_rs": 199 }
    ]
  }
}
```

---

### 3. Send Payment Reminder (Admin)

```
POST /api/v1/admin/crm/invoices/:id/send-reminder
Authorization: Bearer JWT (admin_super | admin_finance | admin_operations)
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "invoice_id": "inv_uuid_001",
    "reminder_sent_via": ["EMAIL", "WHATSAPP"],
    "sent_at": "2026-07-24T10:00:00Z"
  }
}
```

**Error Responses**

| HTTP | Error Code | Description |
|---|---|---|
| 400 | `INVOICE_ALREADY_PAID` | Cannot send reminder on a PAID invoice |
| 404 | `INVOICE_NOT_FOUND` | Invoice ID does not exist |

---

### 4. Mark Invoice as Paid (Admin)

```
POST /api/v1/admin/crm/invoices/:id/mark-paid
Authorization: Bearer JWT (admin_super | admin_finance)
Content-Type: application/json
```

**Request Body**
```json
{
  "payment_date": "2026-07-24",
  "payment_mode": "NEFT",
  "reference_number": "NEFT-TXN-20260724-001"
}
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "invoice_id": "inv_uuid_001",
    "status": "PAID",
    "paid_at": "2026-07-24T00:00:00Z",
    "marked_by": "admin_uuid_001",
    "subscription_status_updated_to": "ACTIVE"
  }
}
```

---

### 5. Pharmacy - List Own Invoices

```
GET /api/v1/pharmacy/billing/invoices
Authorization: Bearer JWT (pharmacy_owner)
```

**Query Parameters**

| Parameter | Type | Description |
|---|---|---|
| `page` | integer | Default 1 |
| `limit` | integer | Default 20 |

**Response 200**
```json
{
  "success": true,
  "data": {
    "invoices": [
      {
        "id": "inv_uuid_001",
        "invoice_number": "NMM-INV-2026-07-001234",
        "billing_period": "July 2026",
        "total_amount_rs": 1059,
        "status": "PAID",
        "due_date": "2026-07-01",
        "paid_at": "2026-07-03T10:00:00Z"
      }
    ]
  },
  "meta": { "page": 1, "limit": 20, "total": 7 }
}
```

---

### 6. Pharmacy - Get Invoice Detail + Download

```
GET /api/v1/pharmacy/billing/invoices/:id
Authorization: Bearer JWT (pharmacy_owner)
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "id": "inv_uuid_001",
    "invoice_number": "NMM-INV-2026-07-001234",
    "download_url": "https://cdn.nammamedmate.com/invoices/NMM-INV-2026-07-001234.pdf",
    "download_expires_at": "2026-07-24T11:00:00Z",
    "line_items": [
      { "description": "STARTER Plan - Monthly", "amount_rs": 699 },
      { "description": "E-Invoice Integration", "amount_rs": 199 }
    ],
    "subtotal_rs": 898,
    "gst_amount_rs": 161,
    "total_amount_rs": 1059,
    "status": "PAID"
  }
}
```

---

### 7. Pharmacy - Initiate Payment

```
POST /api/v1/pharmacy/billing/pay
Authorization: Bearer JWT (pharmacy_owner)
Content-Type: application/json
```

**Request Body**
```json
{
  "invoice_id": "inv_uuid_002",
  "payment_method": "UPI"
}
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "invoice_id": "inv_uuid_002",
    "payment_gateway": "Cashfree",
    "payment_session_id": "session_pay_uuid_001",
    "expires_at": "2026-07-24T10:30:00Z"
  }
}
```

**Error Responses**

| HTTP | Error Code | Description |
|---|---|---|
| 400 | `INVOICE_ALREADY_PAID` | Invoice already in PAID status |
| 404 | `INVOICE_NOT_FOUND` | Invoice does not belong to pharmacy |

---

## Data Model

### SaaSInvoice

| Field | Type | Constraints | Description |
|---|---|---|---|
| `id` | UUID v4 | PK | Internal identifier |
| `invoice_number` | VARCHAR(30) | UNIQUE, NOT NULL | Human-readable invoice number |
| `account_id` | UUID | FK ? crm_accounts | Subscriber account |
| `subscription_id` | UUID | FK ? subscriptions | Linked subscription |
| `billing_period_from` | DATE | NOT NULL | Period start |
| `billing_period_to` | DATE | NOT NULL | Period end |
| `subtotal_rs` | DECIMAL(10,2) | NOT NULL | Pre-GST total |
| `gst_rate_pct` | DECIMAL(4,2) | DEFAULT 18.00 | GST rate |
| `gst_amount_rs` | DECIMAL(10,2) | NOT NULL | GST amount |
| `total_amount_rs` | DECIMAL(10,2) | NOT NULL | Amount including GST |
| `status` | ENUM | DEFAULT DUE | `PAID`, `DUE`, `OVERDUE`, `DUNNING`, `WAIVED` |
| `due_date` | DATE | NOT NULL | Payment due date |
| `paid_at` | TIMESTAMPTZ | NULLABLE | When paid |
| `payment_mode` | VARCHAR(20) | NULLABLE | UPI, NEFT, CARD, etc. |
| `reference_number` | VARCHAR(100) | NULLABLE | Payment reference |
| `marked_paid_by` | UUID | NULLABLE FK ? admin_users | Admin who marked paid |
| `dunning_step` | INTEGER | DEFAULT 0 | Current dunning step (0-4) |
| `created_at` | TIMESTAMPTZ | DEFAULT NOW() | Invoice generation time |

### SaaSInvoiceLineItem

| Field | Type | Constraints | Description |
|---|---|---|---|
| `id` | UUID v4 | PK | Line item identifier |
| `invoice_id` | UUID | FK ? saas_invoices | Parent invoice |
| `description` | VARCHAR(200) | NOT NULL | Line item description |
| `sac_code` | VARCHAR(10) | DEFAULT '9983' | GST SAC code |
| `amount_rs` | DECIMAL(10,2) | NOT NULL | Pre-GST line amount |
| `item_type` | ENUM | NOT NULL | `PLAN`, `ADDON`, `CREDIT` |

---

## Acceptance Criteria

1. Invoice generated on subscription start contains: plan line item, SAC code 9983, 18% GST, and correct total.
2. Admin billing dashboard chips (collected, due, overdue) match the actual invoice status counts.
3. Sending a reminder on a PAID invoice returns HTTP 400 `INVOICE_ALREADY_PAID`.
4. Admin marking an invoice paid with `payment_mode = NEFT` transitions status to PAID and subscription to ACTIVE.
5. DSO is computed correctly as average `(paid_at ? due_date)` days across all PAID invoices in the period.
6. Pharmacy owner can download a signed PDF invoice via the `download_url`.
7. Dunning step increments on the correct days (Day 3, 7, 10, 14) via automation engine.
8. Account EXPIRED transition (Day 14 dunning) locks premium modules immediately.
9. Collection rate = PAID / (PAID + OVERDUE) - 100 matches arithmetic on the dashboard.
10. Waived invoice does not appear in overdue count or collection rate denominator.

---

## Dependencies

| Dependency | Description |
|---|---|
| Subscription Management (STORY-002) | Subscription status updates on payment |
| Payment Gateway | Cashfree / payment checkout initiation |
| Automation Engine | Dunning sequence orchestration |
| Notification Engine | Email + WhatsApp reminder delivery |
| PDF Generator | Invoice PDF generation and CDN upload |
| GST Module | GSTIN validation, SAC code management |

---

## Notes

- Invoice number format: `NMM-INV-YYYY-MM-XXXXXX` (zero-padded sequential number per month).
- PDF invoices are generated async upon invoice creation and stored on CDN; `download_url` has a signed expiry of 1 hour for security.
- Dunning is driven by the Automation Engine (EPIC-011); the billing module only sets `dunning_step` and triggers the automation event.
- WAIVED invoices require a mandatory `waive_reason` stored in `SaaSInvoice.waive_reason` (field omitted from main model above for brevity; add in implementation).
