# EPIC-022: External Integrations

| Field | Value |
|-------|-------|
| Epic ID | EPIC-022 |
| Epic Name | External Integrations |
| Product | Namma MedMate |
| Domain | Third-Party Integrations, Payments, Maps, Government APIs |
| Priority | P1 - Critical |
| Status | In Development |
| Owner | Platform Squad |
| Last Updated | 2026-07-24 |

## Overview

EPIC-022 covers all external third-party service integrations for Namma MedMate. It includes Razorpay payment gateway and RazorpayX payout integration, Google Maps APIs for geocoding and routing, Indian government APIs (GSTN, DigiLocker, drug licence registry, FSSAI) for KYC verification, GST e-invoicing IRN portal integration, Tally and Zoho Books accounting sync, and the communication service integrations (MSG91, Meta WhatsApp, Firebase FCM, SendGrid) health and configuration layer.

## Goals

- Provide reliable payment collection and payout disbursement via Razorpay
- Enable accurate address geocoding and delivery routing via Google Maps
- Automate pharmacy KYC verification against government databases
- Support GST e-invoicing compliance for eligible B2B transactions
- Enable pharmacy owners to sync sales data to their accounting software
- Monitor and manage all communication channel integrations from a central admin UI

## Stories

| Story ID | Title | Description |
|----------|-------|-------------|
| STORY-001 | Razorpay Integration | Payment gateway and RazorpayX payout integration |
| STORY-002 | Maps & Geolocation | Google Maps API integration |
| STORY-003 | Government API Integration | GSTN, DigiLocker, drug licence, FSSAI |
| STORY-004 | E-Invoicing IRN | GST e-invoicing with NIC portal |
| STORY-005 | Accounting Integration | Tally and Zoho Books sync |
| STORY-006 | Communication Integrations | MSG91, Meta WhatsApp, Firebase, SendGrid health |

## Cross-Cutting Concerns

- All external API credentials are stored in AWS Secrets Manager (never in environment variables or source code)
- All external API calls are logged (request + response metadata, not full bodies for PII) for debugging and cost tracking
- Webhook endpoints validate provider-specific signatures before processing
- External API failures return graceful errors with manual-review fallback
- Rate limits are respected per provider; platform-level rate limit tracking prevents overuse

## Dependencies

| Dependency | Type |
|-----------|------|
| AWS Secrets Manager | Credential storage |
| EPIC-005 Finance | Consumer of payment/payout APIs |
| EPIC-006 Pharmacy | Consumer of KYC and maps APIs |
| EPIC-004 Dispatch | Consumer of maps APIs |
| EPIC-017 Notifications | Consumer of communication APIs |
