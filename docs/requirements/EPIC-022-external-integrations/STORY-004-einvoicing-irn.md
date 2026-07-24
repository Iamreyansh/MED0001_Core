# STORY-004: E-Invoicing IRN

| Field | Value |
|-------|-------|
| Story ID | EPIC-022-STORY-004 |
| Epic | EPIC-022 External Integrations |
| Title | E-Invoicing IRN |
| Priority | P1 |
| Status | In Development |
| Role | Internal service + admin_compliance, pharmacy_owner |
| Last Updated | 2026-07-24 |

## Overview

The E-Invoicing IRN story integrates the NIC (National Informatics Centre) e-invoice portal to generate Invoice Reference Numbers (IRN) for B2B GST e-invoices as mandated by the GST Council. IRN generation produces a digitally signed invoice JSON and a QR code that must be printed on the physical invoice. IRN cancellation within 24 hours is supported. The integration is activated per pharmacy via a feature toggle (applicable only to pharmacies meeting the annual turnover threshold). An IRN status check endpoint enables reconciliation.

## User Roles

| Role | Access |
|------|--------|
| Internal services | Generate and cancel IRNs (service-to-service) |
| admin_compliance | View IRN status; trigger manual generation |
| pharmacy_owner | View IRN status for their invoices |

## Business Rules

1. **Applicability Threshold**: E-invoicing is mandatory for B2B transactions for businesses with annual aggregate turnover (AATO) exceeding Rs 5 crore. For Namma MedMate as the marketplace operator, this applies to B2B invoices raised by/through large pharmacies. The feature is toggled per pharmacy.
2. **IRN Uniqueness**: Each IRN is unique per seller GSTIN + buyer GSTIN + document type + financial year + invoice number. Duplicate IRN generation for the same combination returns the existing IRN (idempotent).
3. **24-Hour Cancellation Window**: IRN can be cancelled only within 24 hours of generation. After 24 hours, cancellation is blocked. A cancelled IRN cannot be regenerated or reused.
4. **Signed Invoice JSON**: The NIC portal returns a digitally signed invoice JSON (`signed_invoice_json`) with a digital signature. This signed JSON must be stored and can be used to verify invoice authenticity.
5. **QR Code**: The NIC portal returns a QR code URL (`qr_code_url`). The QR code contains the IRN plus key invoice details. It must appear on printed and digital invoices.
6. **Auto-IRN Generation**: When `e_invoicing_enabled` is true for a pharmacy, IRN generation is automatically triggered for every B2B invoice at the time of invoice finalization. Manual generation is available as a fallback.
7. **Feature Flag**: E-invoicing is controlled by a pharmacy-level `e_invoicing_enabled` setting. When false, no IRN is generated regardless of transaction type.
8. **NIC Portal Integration**: The platform uses a GSP (GST Suvidha Provider) as the intermediary to the NIC portal. The GSP handles authentication token management (JWT, refreshed every 24 hours) and request signing.
9. **Invoice Data Schema**: The `invoice_data` object in the generate request must conform to the GST e-invoice schema (SCHEMA 1.1). The platform validates the schema locally before calling the NIC portal.
10. **IRN Printed on Invoice**: The platform's invoice generation service automatically appends IRN, ACK number, ACK date, and QR code to invoice PDFs when e-invoicing is enabled.

## API Endpoints

### POST /api/v1/integrations/einvoice/generate-irn

Generate an Invoice Reference Number for a B2B invoice.

**Auth**: Service-to-service JWT (internal only)

**Request Body**
```json
{
  "invoice_data": {
    "seller_gstin": "29ABCDE1234F1Z5",
    "buyer_gstin": "27XYZAB5678G1Z3",
    "invoice_number": "INV-2026-07-001",
    "invoice_date": "2026-07-24",
    "supply_type": "B2B",
    "invoice_type": "INV",
    "items": [
      {
        "sl_no": 1,
        "product_name": "Metformin 500mg Tablet",
        "hsn_code": "30049099",
        "qty": 100,
        "unit": "NOS",
        "unit_price": 8.40,
        "discount": 0,
        "assbl_value": 840.00,
        "gst_rate": 12,
        "igst_amount": 0,
        "cgst_amount": 50.40,
        "sgst_amount": 50.40,
        "total": 940.80
      }
    ],
    "tax_amounts": {
      "taxable_value": 840.00,
      "igst": 0,
      "cgst": 50.40,
      "sgst": 50.40,
      "total_invoice_value": 940.80
    }
  }
}
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "irn": "a85e9a93af0ed3c3f1c7e2fd4b08e3a7f4c2a1b0c9d3e5f8a7b6c4d2e1f0a9b8",
    "ack_number": "232410141234567",
    "ack_date": "2026-07-24T10:30:00Z",
    "qr_code_url": "data:image/png;base64,iVBORw0KGgo...",
    "signed_invoice_json": "{\"Version\":\"1.1\",\"Irn\":\"a85e9a93...\",...}",
    "already_existed": false,
    "generated_at": "2026-07-24T10:30:02Z"
  },
  "meta": {}
}
```

**Error Table**

| HTTP Code | Error Code | Condition |
|-----------|-----------|-----------|
| 422 | INVALID_INVOICE_SCHEMA | invoice_data fails GST e-invoice schema validation |
| 422 | SELLER_GSTIN_NOT_REGISTERED | Seller GSTIN not found in e-invoice portal |
| 422 | DUPLICATE_IRN | IRN already exists (returns existing IRN in data) |
| 503 | NIC_PORTAL_UNAVAILABLE | NIC portal unreachable |

---

### POST /api/v1/integrations/einvoice/cancel-irn

Cancel an IRN (within 24 hours of generation).

**Auth**: Service-to-service JWT (internal only)

**Request Body**
```json
{
  "irn": "a85e9a93af0ed3c3f1c7e2fd4b08e3a7f4c2a1b0c9d3e5f8a7b6c4d2e1f0a9b8",
  "cancel_reason_code": "1",
  "cancel_remark": "Invoice issued in error. Correct invoice will be issued separately."
}
```

**Cancel Reason Codes**: `1` = Duplicate, `2` = Data Entry Error, `3` = Order Cancelled, `4` = Others

**Response 200**
```json
{
  "success": true,
  "data": {
    "irn": "a85e9a93af0ed3c3f1c7e2fd4b08e3a7f4c2a1b0c9d3e5f8a7b6c4d2e1f0a9b8",
    "status": "CANCELLED",
    "cancel_reason_code": "1",
    "cancelled_at": "2026-07-24T11:00:00Z"
  },
  "meta": {}
}
```

**Error Table**

| HTTP Code | Error Code | Condition |
|-----------|-----------|-----------|
| 422 | IRN_CANCELLATION_WINDOW_EXPIRED | More than 24 hours since IRN generation |
| 422 | IRN_ALREADY_CANCELLED | IRN already in CANCELLED status |
| 404 | IRN_NOT_FOUND | IRN not found in NIC portal |

---

### GET /api/v1/integrations/einvoice/status/:irn

Check the current status of an IRN.

**Auth**: Service-to-service JWT (internal only)

**Path Parameters**

| Parameter | Type | Description |
|-----------|------|-------------|
| irn | string | 64-character hex IRN |

**Response 200**
```json
{
  "success": true,
  "data": {
    "irn": "a85e9a93af0ed3c3f1c7e2fd4b08e3a7f4c2a1b0c9d3e5f8a7b6c4d2e1f0a9b8",
    "status": "ACTIVE",
    "ack_number": "232410141234567",
    "ack_date": "2026-07-24T10:30:00Z",
    "seller_gstin": "29ABCDE1234F1Z5",
    "buyer_gstin": "27XYZAB5678G1Z3",
    "invoice_number": "INV-2026-07-001",
    "invoice_date": "2026-07-24",
    "total_invoice_value": 940.80,
    "generated_at": "2026-07-24T10:30:02Z",
    "cancelled_at": null
  },
  "meta": {}
}
```

---

## Data Models

### einvoice_irn_records

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| pharmacy_id | UUID | FK ? pharmacies |
| platform_invoice_id | UUID | FK ? invoices |
| irn | VARCHAR(64) | Unique |
| ack_number | VARCHAR(20) | |
| ack_date | TIMESTAMPTZ | |
| seller_gstin | VARCHAR(15) | |
| buyer_gstin | VARCHAR(15) | |
| invoice_number | VARCHAR(50) | |
| invoice_date | DATE | |
| total_invoice_value | DECIMAL(14,2) | |
| qr_code_url | TEXT | Base64 data URI or S3 URL |
| signed_invoice_json | TEXT | Digitally signed invoice JSON |
| status | VARCHAR(10) | ACTIVE, CANCELLED |
| cancel_reason_code | VARCHAR(2) | Nullable |
| cancel_remark | TEXT | Nullable |
| generated_at | TIMESTAMPTZ | |
| cancelled_at | TIMESTAMPTZ | Nullable |

## Acceptance Criteria

1. **AC-001**: POST /generate-irn with invalid invoice schema returns `422 INVALID_INVOICE_SCHEMA` with the failing field in the error message.
2. **AC-002**: POST /generate-irn for a duplicate invoice (same seller+buyer+invoice_number+FY) returns the existing IRN with `already_existed: true`.
3. **AC-003**: POST /cancel-irn more than 24 hours after generation returns `422 IRN_CANCELLATION_WINDOW_EXPIRED`.
4. **AC-004**: GET /status/:irn for a cancelled IRN returns `status: CANCELLED` with `cancelled_at` timestamp.
5. **AC-005**: When `e_invoicing_enabled: false` for a pharmacy, IRN generation is skipped and `irn: null` appears on the invoice (no error raised).
6. **AC-006**: The `signed_invoice_json` stored in `einvoice_irn_records` contains a valid digital signature from NIC (verifiable independently).
7. **AC-007**: A cancelled IRN cannot be regenerated with the same `invoice_number`; a new invoice number is required for a replacement invoice.
8. **AC-008**: All e-invoice API calls to the NIC portal are logged (request summary, response status, latency) in the audit log.

## Dependencies

| Dependency | Type | Notes |
|-----------|------|-------|
| NIC E-Invoice Portal | External | IRN generation and cancellation |
| GST Suvidha Provider (GSP) | Intermediary | Authentication and request routing |
| EPIC-005 Finance | Consumer | Invoice finalization trigger |
| EPIC-006 Pharmacy | Config | `e_invoicing_enabled` setting |
| AWS S3 | Storage | Signed invoice JSON storage (large files) |

## Notes

- The GSP (GST Suvidha Provider) token expires every 24 hours. The platform refreshes the token proactively 1 hour before expiry using a cron job. Token refresh failures trigger a CRITICAL alert.
- IRN generation is only relevant for B2B invoices (buyer has a GSTIN). B2C invoices (consumer purchases) do not require IRN.
- Schema version: GST e-invoice schema version 1.1 (current as of 2026). Monitor GST Council notifications for schema updates.
