# STORY-001: Cashfree Integration

| Field | Value |
|-------|-------|
| Story ID | EPIC-022-STORY-001 |
| Epic | EPIC-022 External Integrations |
| Title | Cashfree Integration |
| Priority | P1 |
| Status | In Development |
| Role | Internal service + admin_finance |
| Last Updated | 2026-08-26 |

## Overview

The Cashfree Integration story covers payment collection and payout disbursement using [Cashfree Payments](https://www.cashfree.com/) Payment Gateway (customer orders, refunds, UPI VPA checks) and Cashfree Payouts (pharmacy and rider bank transfers). All amounts use paise. Credentials live in AWS Secrets Manager (`med0001-{env}/cashfree`).

## User Roles

| Role | Access |
|------|--------|
| Internal services | Call payment and payout endpoints (service-to-service) |
| admin_finance | View payout logs; verify beneficiaries |

## Business Rules

1. **Paise Convention**: All monetary values are in paise (1 Rs = 100 paise).
2. **Webhook Signature Verification**: Cashfree webhooks use HMAC-SHA256; signature in `x-webhook-signature` (with timestamp). Unverified webhooks return 400.
3. **Webhook Idempotency**: Use Cashfree payment/order/transfer ids as idempotency keys; duplicate events are ignored.
4. **Payout Mode**: IMPS for payouts ≤ Rs 2,00,000; NEFT above that unless overridden.
5. **Beneficiary Reuse**: Create one Cashfree beneficiary per pharmacy/rider bank account; reuse on subsequent payouts.
6. **Failed Payout Retry**: Retry once after 1 hour; then flag for manual review.
7. **Test vs Live Mode**: `cashfree_mode: TEST|LIVE` (audit flag); API host follows Cashfree sandbox vs production.
8. **Order Session**: Create PG order; return `payment_session_id` for Cashfree JS checkout.
9. **Refunds**: Via Cashfree refund API; webhook updates ledger. Partial refunds supported.
10. **UPI VPA Verification**: Validate VPA before saving a payment method (format-only when keys are placeholders).

## API Endpoints

### POST /api/v1/integrations/cashfree/create-order

**Auth**: Service-to-service (`X-Internal-Token`)

**Request**: `{ "amount_paise", "currency": "INR", "receipt", "notes" }`

**Response 200**: `{ "cashfree_order_id", "payment_session_id", "amount_paise", "currency", "receipt", "status", "created_at" }`

| HTTP | Error | Condition |
|------|-------|-----------|
| 400 | AMOUNT_TOO_SMALL | amount_paise &lt; 100 |
| 503 | CASHFREE_UNAVAILABLE | Cashfree API error |

### POST /api/v1/integrations/cashfree/verify-upi

Validate UPI VPA before save.

### POST /api/v1/integrations/cashfree/beneficiaries

Create/reuse payout beneficiary for pharmacy or rider.

### POST /api/v1/integrations/cashfree/payouts

Initiate payout (IMPS/NEFT). Requires `Idempotency-Key`.

### POST /api/v1/payments/webhook/cashfree

PG lifecycle events (payment success/failed, refund).

### POST /api/v1/webhooks/cashfree/payout

Payout success/failure events.

## Data Model

| Table | Key columns |
|-------|-------------|
| `cashfree_payment_records` | `cashfree_order_id`, `cashfree_payment_id`, status |
| `cashfree_beneficiaries` | `beneficiary_id`, entity type/id, bank/UPI |
| `cashfree_payout_records` | `cashfree_transfer_id`, amount_paise, status, utr |

Orders/payments/refunds/settlements store `gateway_*` / `payout_provider_*` ids (migrated from Cashfree columns).

## Acceptance Criteria

1. **AC-001**: Create-order returns `payment_session_id` and persists a payment record.
2. **AC-002**: Invalid webhook signature → 400; no state change.
3. **AC-003**: Duplicate webhook for same payment id is idempotent.
4. **AC-004**: Payout reuses existing beneficiary for same bank details.
5. **AC-005**: Failed payout retries once after 1h then flags manual review.
6. **AC-006**: Blank keys → stub client (local/CI); staging/prod require Secrets Manager values.
7. **AC-007**: Refund webhook updates refund status and ledger.
8. **AC-008**: `cashfree_mode` TEST|LIVE is recorded; live keys hit production Cashfree APIs.

## Dependencies

| Dependency | Type |
|------------|------|
| Cashfree Payment Gateway | External |
| Cashfree Payouts | External |
| AWS Secrets Manager | Credential storage |
| EPIC-012 Payments | Consumer |
