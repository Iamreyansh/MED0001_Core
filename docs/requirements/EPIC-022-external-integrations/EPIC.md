# EPIC-022: External Integrations

| Field | Value |
|-------|-------|
| Epic ID | EPIC-022 |
| Epic Name | External Integrations |
| Product | Namma MedMate |
| Domain | Third-Party Integrations, Payments, Maps |
| Priority | P1 - Critical |
| Status | In Development |
| Owner | Platform Squad |
| Last Updated | 2026-08-26 |

## Overview

EPIC-022 covers external third-party integrations for Namma MedMate: Cashfree Payment Gateway and Payouts, and Google Maps for geocoding and routing. Government KYC APIs, e-invoicing, accounting sync, and multi-vendor comms control planes are out of scope (pharmacy KYC is manual admin review; SMS/push live under EPIC-017 via Twilio and FCM).

## Goals

- Reliable payment collection and payout disbursement via Cashfree
- Accurate address geocoding and delivery routing via Google Maps

## Stories

| Story ID | Title | Description |
|----------|-------|-------------|
| STORY-001 | Cashfree Integration | Payment gateway and payouts |
| STORY-002 | Maps & Geolocation | Google Maps API integration |

## Cross-Cutting Concerns

- Credentials in AWS Secrets Manager only
- Log request/response metadata (no full PII bodies)
- Webhook signature verification before processing
- Graceful failures with manual-review fallback where applicable

## Dependencies

| Dependency | Type |
|-----------|------|
| AWS Secrets Manager | Credential storage |
| EPIC-012 Finance | Consumer of payment/payout APIs |
| EPIC-004 Dispatch | Consumer of maps APIs |
| Cashfree | Payments + Payouts |
| Google Maps Platform | Geocode / Distance Matrix / Directions |
