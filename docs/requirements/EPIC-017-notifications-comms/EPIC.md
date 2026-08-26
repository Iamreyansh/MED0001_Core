# EPIC-017: Notifications and Communications

| Field | Value |
|-------|-------|
| Epic ID | EPIC-017 |
| Epic Name | Notifications and Communications |
| Product | Namma MedMate |
| Domain | Notifications, Messaging, User Engagement |
| Priority | P1 - Critical |
| Status | In Development |
| Owner | Platform Squad |
| Last Updated | 2026-08-26 |

## Overview

EPIC-017 covers outbound notifications for Namma MedMate via **Firebase FCM** (push) and **Twilio** (SMS, including OTP). WhatsApp and email channels are out of scope. Preferences and in-app history remain.

## Goals

- Deliver order updates and alerts via Push + SMS
- Admin broadcast for platform announcements
- DLT-compliant SMS templates (TRAI)
- Respect user preferences and opt-outs
- Monitor SMS/push health and cost (no multi-vendor fallback)

## Stories

| Story ID | Title | Description |
|----------|-------|-------------|
| STORY-001 | Push Notification Service | Firebase FCM push and device tokens |
| STORY-002 | SMS Service | Twilio SMS for OTP and transactional messages |
| STORY-005 | Notification Preferences | User preference management (push + SMS) |
| STORY-006 | Notification History | In-app inbox and delivery history |

## Notification Channel Priority

| Event Type | Primary Channel | Secondary |
|-----------|----------------|-----------|
| OTP | SMS | - |
| Order Confirmed | Push + SMS | - |
| Order Out for Delivery | Push | SMS |
| Order Delivered | Push | SMS |
| Payment Receipt | Push | SMS |
| KYC Status | SMS | Push |
| Promotional Campaign | Push | SMS |
| Refill Reminder | Push | SMS |

## Dependencies

| Dependency | Type |
|-----------|------|
| EPIC-010 Order Management | Event source |
| EPIC-012 Finance | Payment notifications |
| EPIC-003 Pharmacy Onboarding | KYC status notifications |
| Firebase FCM | Push delivery (HTTP v1) |
| Twilio | SMS / OTP |
