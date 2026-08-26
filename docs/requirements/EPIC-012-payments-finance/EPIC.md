# EPIC-012: Payments and Finance

| Field | Value |
|---|---|
| Epic ID | EPIC-012 |
| Epic Name | Payments and Finance |
| Product | Namma MedMate |
| Domain | Finance & Payments |
| Status | In Progress |
| Priority | P0 - Core Platform |
| Owner | Engineering Lead - Finance |
| Last Updated | 2026-07-24 |

---

## Overview

EPIC-012 governs every rupee that flows through the Namma MedMate platform. It covers payment initiation and verification via Cashfree (UPI, Card, COD), the Namma Money in-app wallet for customers, weekly settlement disbursements to pharmacies via Cashfree Payouts, rider earnings payouts, the full refund lifecycle, COD float management at the finance level, Indian tax compliance (TCS Section 194-O, GST on commission, TDS), an append-only financial ledger, and the real-time P&L overview dashboard for the admin finance team. The epic is designed to be fully auditable, with every money movement creating a ledger entry and all Indian regulatory filing obligations tracked and exportable.

---

## Goals

- Process UPI and card payments securely via Cashfree with server-side signature verification.
- Ensure weekly pharmacy settlements are accurate (GMV - commission ? TCS) and released on time.
- Automate rider payout computation including incentives, tips, and COD deductions.
- Maintain a zero-discrepancy financial ledger that feeds directly into Tally/Zoho accounting exports.
- Track and export TCS (GSTR-8), TDS 194-O, and GST liabilities for regulatory compliance.

---

## Stories

| Story ID | Title | Status | Priority | Est. Sprints |
|---|---|---|---|---|
| EPIC-012/STORY-001 | Payment Processing (UPI / Card / COD) | Draft | P0 | 2 |
| EPIC-012/STORY-002 | Namma Money Wallet Operations | Draft | P0 | 2 |
| EPIC-012/STORY-003 | Pharmacy Settlements | Draft | P0 | 2 |
| EPIC-012/STORY-004 | Rider Payouts | Draft | P0 | 1 |
| EPIC-012/STORY-005 | Refund Processing | Draft | P0 | 2 |
| EPIC-012/STORY-006 | COD Float Management (Finance Side) | Draft | P1 | 1 |
| EPIC-012/STORY-007 | Tax & GST Management | Draft | P1 | 2 |
| EPIC-012/STORY-008 | Financial Ledger | Draft | P1 | 1 |
| EPIC-012/STORY-009 | Financial Overview Dashboard | Draft | P1 | 1 |

**Total Stories:** 9

---

## Roles Involved

| Role | Involvement |
|---|---|
| `customer` | Payment initiation, wallet balance/transactions, refund status |
| `pharmacy_owner` | Settlement history, settlement detail view |
| `rider` | COD summary, deposit request, payout history |
| `admin_finance` | Settlement release, refund processing, payout release, ledger, tax filings |
| `admin_super` | All finance operations |
| `admin_operations` | COD float monitoring (read-only) |

---

## Key Data Entities

| Entity | Description |
|---|---|
| `Payment` | Cashfree payment record tied to an order |
| `WalletAccount` | Customer wallet with current balance |
| `WalletTransaction` | Debit/credit ledger entry with FIFO expiry |
| `PharmacySettlement` | Weekly settlement cycle record per pharmacy |
| `SettlementLineItem` | Per-order GMV + commission breakdown within a settlement |
| `RiderPayout` | Weekly earnings payout record per rider |
| `Refund` | Refund record with status, source, and destination |
| `CODFloat` | COD collection/deposit tracking at finance level |
| `TaxFiling` | Tax obligation tracking (GSTR-8, TDS, GST) |
| `TCSRegister` | Monthly TCS collected per pharmacy for GSTR-8 |
| `FinancialLedger` | Append-only ledger entry for every money movement |
| `GatewayFee` | Cashfree fee captured per transaction |

---

## External Dependencies

| Dependency | Used For |
|---|---|
| Cashfree Payment Gateway | UPI / card payment orders and capture |
| Cashfree Webhook | Payment events: captured, failed, refund.processed |
| Cashfree Payouts | Pharmacy settlement payouts, rider payouts |
| EPIC-010 (Order Management) | Order status drives payment, refund, and settlement triggers |
| EPIC-011 (Rider Management) | COD collection data, rider earnings data |
| EPIC-013 (Notifications) | Payment confirmation, refund notification, payout SMS |
| Tally / Zoho Books | CSV export integration for accounting sync |
| GSTN Portal | Tax filing (manual upload of exported data) |

---

## Finance Formulas Reference

| Calculation | Formula |
|---|---|
| Pharmacy Settlement Net | `GMV - commission_pct ? (GMV - 0.01)` *(TCS)* |
| TCS | `GMV - 1%` (0.5% CGST + 0.5% SGST) |
| GST on Commission | `commission_amount - 18%` |
| Delivery Fee | `(base_fee + distance_km - per_km_fee) - surge_multiplier` |
| Rider Delivery Payout | `max(delivery_fee - 70%, Rs 15)` |
| Rider Net Payout | `base_earnings + incentives + tips ? cod_in_hand_deducted` |
| Platform Net Revenue | `GMV - commission_pct ? refunds ? gateway_fees` |

---

## Non-Functional Requirements

| Requirement | Target |
|---|---|
| Payment verify latency | < 500 ms |
| Wallet debit atomicity | DB-level transaction; no partial debit |
| Webhook processing | Idempotent; duplicate events silently ignored |
| Settlement generation | Automated cron every Monday 06:00 IST |
| Ledger entries | Append-only; no UPDATE/DELETE |
| Tax export | CSV download < 5 s for 3-month period |

---

## Compliance Notes

- Platform is an **e-commerce operator** under GST law ? TCS obligation under Section 52 CGST Act.
- TCS rate: 1% of GMV (0.5% CGST + 0.5% SGST); collected from pharmacy settlement.
- TDS Section 194-O applies to pharmacy commissions above Rs 5 lakh annual threshold.
- GSTR-8 filing due by 10th of each following month.
- All payout records must be retained for 7 years per Indian income-tax rules.

---

## Out of Scope (EPIC-012)

- Customer UPI top-up of Namma Money wallet (Phase 2)
- Buy-Now-Pay-Later (BNPL) / EMI payment methods
- Multi-currency support
- Automated direct Tally/Zoho API sync (manual CSV export only in v1)
