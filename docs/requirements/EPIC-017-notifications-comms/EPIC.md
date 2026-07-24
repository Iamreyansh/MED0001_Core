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
| Last Updated | 2026-07-24 |

## Overview

EPIC-017 covers all outbound notification and communication capabilities for Namma MedMate. It integrates Firebase FCM for push notifications, MSG91/Twilio for SMS, Meta WhatsApp Business API for rich conversational messages, and SendGrid/AWS SES for transactional email. User notification preference management allows customers and pharmacies to control which channels and categories they receive. An in-app notification inbox provides a persistent history of messages, while admin tools allow broadcast, delivery log inspection, and channel health monitoring.

## Goals

- Deliver timely order updates, payment receipts, and alerts to customers, pharmacies, and riders
- Provide admin broadcast capability for platform-wide announcements
- Ensure DLT compliance for all SMS templates (TRAI mandate)
- Respect user notification preferences and opt-outs across all channels
- Monitor channel health and cost, with automatic fallback between providers

## Stories

| Story ID | Title | Description |
|----------|-------|-------------|
| STORY-001 | Push Notification Service | Firebase FCM push notification sending and device token management |
| STORY-002 | SMS Service | SMS delivery via MSG91/Twilio for OTP and transactional messages |
| STORY-003 | WhatsApp Business API | Meta WhatsApp Business API for order updates and campaigns |
| STORY-004 | Email Service | Transactional and campaign email delivery |
| STORY-005 | Notification Preferences | User notification preference management |
| STORY-006 | Notification History | Notification inbox and delivery history for customers |

## Notification Channel Priority

| Event Type | Primary Channel | Secondary | Tertiary |
|-----------|----------------|-----------|---------|
| OTP | SMS | - | - |
| Order Confirmed | Push + WhatsApp | SMS | Email |
| Order Out for Delivery | Push | WhatsApp | - |
| Order Delivered | Push + WhatsApp | Email | - |
| Payment Receipt | Email | WhatsApp | - |
| KYC Status | WhatsApp + Email | SMS | - |
| Promotional Campaign | Push | WhatsApp | Email |
| Refill Reminder | Push | WhatsApp | - |

## Dependencies

| Dependency | Type |
|-----------|------|
| EPIC-001 Order Management | Event source for order notifications |
| EPIC-005 Finance | Payment and settlement notifications |
| EPIC-006 Pharmacy Onboarding | KYC status notifications |
| Firebase FCM | Push delivery |
| MSG91 | SMS primary provider |
| Twilio | SMS fallback provider |
| Meta Cloud API | WhatsApp delivery |
| SendGrid | Email primary provider |
| AWS SES | Email fallback provider |
