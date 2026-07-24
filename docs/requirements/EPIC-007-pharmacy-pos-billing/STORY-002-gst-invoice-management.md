# STORY-002: GST Invoice Management - Generation, Customization & Sharing

| Attribute | Value |
|-----------|-------|
| **Story ID** | STORY-002 |
| **Epic** | EPIC-007 - Pharmacy POS & Billing |
| **Priority** | P0 |
| **Complexity** | L |
| **Status** | Draft |

---

## Overview

This story covers invoice generation, template customization, PDF download, and multi-channel sharing for the Namma MedMate Pharmacy Dashboard. Every completed POS sale and online order generates a GST-compliant invoice with line-item HSN codes, GST breakdown by slab, and pharmacy branding. Pharmacists can customize their invoice template (logo, colors, footer text, bank details) and share invoices instantly via WhatsApp, SMS, or email. Invoices are immutable post-generation; corrections create a credit note.

---

## User Roles & Access

| Role | Access Level | Description |
|------|-------------|-------------|
| `pharmacy_owner` | Full read + write | View all invoices, customize settings, share |
| `pharmacy_staff` | Read + share | View invoices and share; cannot edit settings |
| `admin_finance` | Read-only | Cross-pharmacy invoice audit |
| `admin_support` | Read-only | Customer support lookup |
| `customer` | No access (own invoices via customer app only) | Customer invoice access is scoped separately |

---

## Business Rules

1. **Invoice number format.** Format is `{prefix}-{YYYY}-{MM}-{NNNNNN}` where `NNNNNN` is a zero-padded 6-digit sequential counter per pharmacy per month, resetting at the start of each financial month. Example: `INV-2026-07-000042`. The prefix max length is 6 alphanumeric characters.
2. **Invoice immutability.** An invoice is read-only once generated. If a billing error occurs, the pharmacist must create a **credit note** (future story) referencing the original invoice. The original invoice status changes to `CREDIT_NOTE_ISSUED`.
3. **GST breakdown by slab.** For GSTIN-registered pharmacies, the invoice must include a GST summary table breaking down taxable amount and GST collected by slab (e.g., 5%, 12%, 18%). Each line item carries its HSN code for GST compliance.
4. **PDF generation is server-side.** The PDF is generated using headless Chrome (Puppeteer) or WeasyPrint from an HTML template. Thermal template targets 80mm thermal receipt width. Modern and Minimal templates target A4.
5. **WhatsApp share uses an approved template.** The message is sent via the WhatsApp Business API using a pre-approved template that includes the pharmacy name, invoice number, amount, and a secure PDF download link.
6. **Invoice prefix validation.** `invoice_prefix` must be 1-6 alphanumeric characters (uppercase). Changing the prefix does not affect existing invoice numbers.
7. **`show_mrp_savings` line.** When enabled, the invoice shows a "You saved Rs X by shopping at {pharmacy_name}" line below the grand total (savings = sum of (MRP - selling_price) for all items).
8. **Thermal template.** Designed for 80mm - variable-length paper. Condensed layout: single-column, product name + qty + price, no logo. Font size optimized for thermal paper readability.

---

## API Endpoints

### 1. List Invoices

```
GET /api/v1/pharmacy/invoices
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 60 req/min

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `from_date` | date | - | Filter by invoice date (YYYY-MM-DD) |
| `to_date` | date | - | Filter end date |
| `payment_method` | enum | - | `CASH \| UPI \| CARD \| CREDIT \| INSURANCE_TPA` |
| `channel` | enum | - | `COUNTER \| ONLINE` |
| `q` | string | - | Search by invoice number, customer name, phone |
| `page` | integer | `1` | Page |
| `limit` | integer | `20` | Items per page |
| `export` | enum | - | `EXCEL \| PDF` |

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "invoices": [
      {
        "invoice_id": "uuid",
        "invoice_number": "INV-2026-07-000042",
        "date": "2026-07-24",
        "customer_name": "Priya Sharma",
        "customer_phone": "+919876000001",
        "channel": "COUNTER",
        "payment_method": "CASH",
        "items_count": 3,
        "grand_total": 450.00,
        "gst_total": 48.21,
        "payment_status": "PAID"
      }
    ]
  },
  "meta": { "page": 1, "limit": 20, "total": 1284 }
}
```

---

### 2. Invoice Detail

```
GET /api/v1/pharmacy/invoices/:invoice_id
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 120 req/min

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "invoice_id": "uuid",
    "invoice_number": "INV-2026-07-000042",
    "date": "2026-07-24T12:15:00Z",
    "channel": "COUNTER",
    "pharmacy": {
      "name": "Balaji Medical Store",
      "address": "Shop 4, MG Road, Bangalore 560001",
      "phone": "+918022334455",
      "gstin": "29AABCB1234A1Z5",
      "drug_licence": "DL-KA-2020-00456"
    },
    "customer": {
      "name": "Priya Sharma",
      "phone": "+919876000001"
    },
    "prescribing_doctor": "Dr. Ramesh K.",
    "line_items": [
      {
        "product_name": "Paracetamol 500mg Tab",
        "hsn_code": "30049099",
        "batch_number": "BN25100",
        "expiry_date": "2027-06-30",
        "pack_size": 15,
        "quantity": 2,
        "unit_price": 22.50,
        "line_subtotal": 45.00,
        "gst_pct": 12,
        "gst_amount": 4.82,
        "line_total": 45.00
      }
    ],
    "subtotal": 450.00,
    "discount_amount": 0.00,
    "gst_breakdown": [
      { "slab": "12%", "hsn_code": "30049099", "taxable_amount": 321.43, "cgst": 19.29, "sgst": 19.29 },
      { "slab": "5%", "hsn_code": "30049015", "taxable_amount": 95.24, "cgst": 2.38, "sgst": 2.38 }
    ],
    "grand_total": 450.00,
    "payment_method": "CASH",
    "payment_status": "PAID",
    "mrp_savings": 24.00,
    "payment_reference": null
  }
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 404 | `INVOICE_NOT_FOUND` | Invoice ID not found for this pharmacy |

---

### 3. Download Invoice PDF

```
GET /api/v1/pharmacy/invoices/:invoice_id/pdf
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 30 req/min

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `template` | enum | pharmacy setting | `MODERN \| MINIMAL \| THERMAL` - override template for this download |

**Success Response - 200 OK:**

Response body is either:
- `Content-Type: application/pdf` (binary stream), or
- JSON with pre-signed URL

```json
{
  "success": true,
  "data": {
    "pdf_url": "https://cdn.medmate.in/pharmacy/uuid/INV-2026-07-000042.pdf",
    "expires_at": "2026-07-24T14:15:00Z"
  }
}
```

---

### 4. Share Invoice

```
POST /api/v1/pharmacy/invoices/:invoice_id/share
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 20 req/min

**Request Body (application/json):**

```json
{
  "channel": "WHATSAPP | SMS | EMAIL - required",
  "recipient_phone_or_email": "string - required"
}
```

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "channel": "WHATSAPP",
    "recipient": "+919876000001",
    "message_id": "wa_msg_uuid",
    "sent_at": "2026-07-24T12:20:00Z"
  }
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `INVALID_RECIPIENT` | Phone or email format invalid |
| 503 | `CHANNEL_UNAVAILABLE` | WhatsApp/SMS service down |

---

### 5. Get Invoice Settings

```
GET /api/v1/pharmacy/invoice-settings
```

**Authentication:** Bearer JWT - `pharmacy_owner`, `pharmacy_staff`
**Rate Limit:** 30 req/min

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "template": "MODERN",
    "accent_color": "#2563EB",
    "logo_url": "https://cdn.medmate.in/pharmacy/uuid/logo.png",
    "signature_url": null,
    "document_title": "Tax Invoice",
    "invoice_prefix": "INV",
    "signatory_label": "Authorized Signatory",
    "bank_details": {
      "bank_name": "HDFC Bank",
      "account_number": "XXXX1234",
      "ifsc_code": "HDFC0001234",
      "upi_id": "balajimed@hdfcbank"
    },
    "terms_and_conditions": "Medicines once sold will not be taken back or exchanged.",
    "footer_note": "Thank you for shopping at Balaji Medical Store!",
    "show_mrp_savings": true,
    "show_doctor": true,
    "show_hsn": true,
    "print_bank_details": false
  }
}
```

---

### 6. Update Invoice Settings

```
PATCH /api/v1/pharmacy/invoice-settings
```

**Authentication:** Bearer JWT - `pharmacy_owner`
**Rate Limit:** 10 req/min

**Request Body (application/json):**

```json
{
  "template": "MODERN | MINIMAL | THERMAL - optional",
  "accent_color": "string hex color - optional",
  "logo_url": "string URL - optional",
  "signature_url": "string URL - optional",
  "document_title": "string max 50 - optional",
  "invoice_prefix": "string 1-6 alphanumeric uppercase - optional",
  "signatory_label": "string max 100 - optional",
  "bank_details": {
    "bank_name": "string max 100",
    "account_number": "string max 20",
    "ifsc_code": "string 11 chars",
    "upi_id": "string max 50"
  },
  "terms_and_conditions": "string max 1000 - optional",
  "footer_note": "string max 500 - optional",
  "show_mrp_savings": "boolean - optional",
  "show_doctor": "boolean - optional",
  "show_hsn": "boolean - optional",
  "print_bank_details": "boolean - optional"
}
```

**Success Response - 200 OK:**

```json
{
  "success": true,
  "data": {
    "invoice_prefix": "INV",
    "template": "MODERN",
    "updated_at": "2026-07-24T12:30:00Z"
  }
}
```

**Error Responses:**

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `INVALID_PREFIX_FORMAT` | prefix not alphanumeric or > 6 chars |
| 400 | `INVALID_ACCENT_COLOR` | hex color format invalid |
| 400 | `INVALID_IFSC_CODE` | IFSC code not exactly 11 chars |

---

## Data Models

### Invoice

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | UUID | PK | Unique invoice ID |
| `pharmacy_id` | UUID | FK ? Pharmacy, NOT NULL | Issuing pharmacy |
| `invoice_number` | VARCHAR(30) | NOT NULL, UNIQUE per pharmacy | Generated invoice number |
| `cart_id` | UUID | FK ? PosCart, nullable | Source cart (null for online orders) |
| `channel` | ENUM | NOT NULL | COUNTER / ONLINE |
| `customer_id` | UUID | FK ? Customer, nullable | Named customer |
| `customer_name` | VARCHAR(100) | nullable | Walk-in or stored customer name |
| `customer_phone` | VARCHAR(20) | nullable | Customer phone |
| `prescribing_doctor` | VARCHAR(200) | nullable | Doctor name for Rx items |
| `subtotal` | NUMERIC(12,2) | NOT NULL | Pre-discount total |
| `discount_amount` | NUMERIC(12,2) | NOT NULL, default 0 | Applied discount |
| `gst_total` | NUMERIC(12,2) | NOT NULL | Total GST collected |
| `grand_total` | NUMERIC(12,2) | NOT NULL | Final amount payable |
| `payment_method` | ENUM | NOT NULL | CASH / UPI / CARD / COD / CREDIT / INSURANCE_TPA |
| `payment_status` | ENUM | NOT NULL | PAID / PENDING / PARTIAL |
| `payment_reference` | VARCHAR(100) | nullable | UPI ref / TPA auth code |
| `mrp_savings` | NUMERIC(10,2) | NOT NULL, default 0 | Total MRP savings for the customer |
| `status` | ENUM | NOT NULL, default ACTIVE | ACTIVE / CREDIT_NOTE_ISSUED |
| `created_at` | TIMESTAMPTZ | NOT NULL | Invoice generation time |

### InvoiceSettings

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `pharmacy_id` | UUID | PK, FK ? Pharmacy | Owning pharmacy |
| `template` | ENUM | NOT NULL, default MODERN | MODERN / MINIMAL / THERMAL |
| `accent_color` | VARCHAR(7) | NOT NULL, default #2563EB | Hex color code |
| `logo_url` | TEXT | nullable | Pharmacy logo CDN URL |
| `signature_url` | TEXT | nullable | Signatory image URL |
| `document_title` | VARCHAR(50) | NOT NULL, default 'Tax Invoice' | Invoice heading |
| `invoice_prefix` | VARCHAR(6) | NOT NULL, default 'INV' | Invoice number prefix |
| `signatory_label` | VARCHAR(100) | NOT NULL | Label above signature |
| `bank_details` | JSONB | nullable | Bank account JSON object |
| `terms_and_conditions` | TEXT | nullable | T&C text |
| `footer_note` | VARCHAR(500) | nullable | Footer note |
| `show_mrp_savings` | BOOLEAN | NOT NULL, default true | Show savings line |
| `show_doctor` | BOOLEAN | NOT NULL, default true | Show prescribing doctor |
| `show_hsn` | BOOLEAN | NOT NULL, default true | Show HSN codes |
| `print_bank_details` | BOOLEAN | NOT NULL, default false | Print bank details on invoice |
| `updated_at` | TIMESTAMPTZ | NOT NULL | Last update |

---

## Acceptance Criteria

- [ ] Given `GET /invoices/:invoice_id`, then the response includes `gst_breakdown` with correct CGST and SGST split per slab (each = half of total GST for intra-state).
- [ ] Given `PATCH /invoice-settings` with `invoice_prefix = "PHARM1"`, then the update succeeds and the next generated invoice uses `PHARM1-YYYY-MM-NNNNNN` format.
- [ ] Given `PATCH /invoice-settings` with `invoice_prefix = "TOOLONGPREFIX"`, then a 400 `INVALID_PREFIX_FORMAT` error is returned.
- [ ] Given `GET /invoices/:invoice_id/pdf?template=THERMAL`, then a PDF optimized for 80mm thermal paper is returned.
- [ ] Given `POST /invoices/:invoice_id/share` with `channel=WHATSAPP`, then the WhatsApp API is called with an approved template and a PDF download link; `sent_at` is recorded.
- [ ] Given a completed checkout, then the generated invoice's `invoice_number` is sequential and does not skip or repeat numbers for the given pharmacy-month.
- [ ] Given `GET /invoices` with `export=EXCEL`, then a downloadable `.xlsx` file is returned with all matching invoice rows.
- [ ] Given `show_mrp_savings = true` in settings, then the invoice PDF and `GET /invoices/:id` response include the `mrp_savings` line showing the correct total savings.

---

## Dependencies

- **EPIC-007 / STORY-001 (POS):** Invoice is created by the checkout operation.
- **EPIC-006 / STORY-001 (Inventory):** HSN codes and GST slabs sourced from `PharmacyProduct`.
- **EPIC-010 (Notifications):** WhatsApp/SMS sharing uses the notification service.
- **PDF Service:** Server-side PDF generation must support both A4 (Modern/Minimal) and thermal (80mm) layouts.

---

## Notes

- Invoice PDFs should be pre-generated and cached in cloud storage (S3) at checkout time. Subsequent `GET /pdf` requests return the cached pre-signed URL.
- For online orders (EPIC-004), `channel = ONLINE` and the `cart_id` is null; the invoice is created by the order fulfilment service.
- The sequential counter for invoice numbers should use a database sequence per `(pharmacy_id, year, month)` to avoid duplicates under concurrent writes.
