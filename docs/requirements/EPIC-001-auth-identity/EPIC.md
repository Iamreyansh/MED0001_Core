# EPIC-001: Authentication & Identity

| Attribute | Value |
|-----------|-------|
| **Epic ID** | EPIC-001 |
| **Domain** | Authentication & Identity |
| **Priority** | P0 |
| **Status** | Draft |

## Overview

EPIC-001 covers all authentication, session management, and role-based access control concerns across all four platform sides: Customer App, Pharmacy Dashboard, Admin HQ, and the Autonomous Operations engine. It establishes the identity fabric that every other epic depends on, implementing secure OTP-based login for customers, credential-based login with multi-pharmacy context switching for pharmacy staff, MFA-enforced login for admin staff, and a JWT rotation strategy with full device/session management. Role-based access control is enforced server-side on every request, with granular permissions defined per role across both admin and pharmacy domains.

## Goals

1. Provide frictionless, secure OTP authentication for customers with rate-limiting and cooldown mechanisms.
2. Enable pharmacy staff to seamlessly switch between multiple pharmacy contexts without re-authenticating.
3. Enforce MFA for all admin_super logins and offer MFA opt-in for other admin roles.
4. Implement JWT access/refresh token rotation with per-role TTLs and full session revocation capability.
5. Deliver granular, server-side RBAC with customisable pharmacy staff roles and fixed admin roles.

## Scope

### In Scope
- Customer OTP authentication (send + verify) with rate limiting
- Pharmacy staff email/phone + password login and POS PIN quick login
- Admin email + password login with TOTP MFA enrollment and verification
- JWT access token (15 min TTL) and refresh token (role-specific TTL) lifecycle
- Token rotation, single-session logout, and full session revocation
- Active session listing and per-device session management
- Admin built-in role definitions and permission matrix
- Pharmacy custom role creation and permission assignment
- Audit logging of all auth events

### Out of Scope
- Social OAuth (Google, Apple) - future roadmap
- Biometric authentication - handled at device level
- Customer MFA - future roadmap
- SSO / SAML for enterprise pharmacy chains - future roadmap

## Stories

| Story ID | Title | Priority | Complexity |
|----------|-------|----------|------------|
| STORY-001 | Customer Mobile OTP Authentication | P0 | M |
| STORY-002 | Pharmacy Staff Authentication | P0 | M |
| STORY-003 | Admin Staff Authentication & MFA | P0 | M |
| STORY-004 | JWT Token Management & Session Control | P0 | L |
| STORY-005 | Role-Based Access Control (RBAC) | P0 | L |

## Success Metrics

- OTP delivery success rate ? 99%
- OTP verification latency ? 500 ms p95
- Zero incidents of session token reuse after rotation
- Admin MFA adoption: 100% for admin_super within 30 days of launch
- RBAC permission-check middleware overhead ? 5 ms p99

## Dependencies

- EPIC-000 (Infrastructure) - SMS gateway (MSG91 / Twilio), Redis for session store, Cashfree for payment method tokenisation
