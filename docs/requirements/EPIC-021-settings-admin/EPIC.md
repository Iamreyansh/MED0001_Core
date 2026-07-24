# EPIC-021: Settings & Platform Administration

| Attribute | Value |
|-----------|-------|
| **Epic ID** | EPIC-021 |
| **Domain** | Platform Administration |
| **Priority** | P0 |
| **Status** | Draft |

## Overview

EPIC-021 covers the internal control-plane tooling for operating and governing the Namma MedMate platform. It provides Admin HQ with the ability to manage the admin team itself (invitations, roles, suspensions), control feature availability via feature flags with percentage rollouts, maintain a tamper-proof audit trail of every admin action, govern global platform configuration (fees, commission rates, SLAs, KYC, rider settings), and view the complete admin role-permission matrix. Together, these capabilities give the platform the operational visibility and governance mechanisms needed to run a compliant, observable, and adaptable marketplace.

## Goals

1. Enable admin_super to invite, manage, and suspend admin team members with full lifecycle control.
2. Provide a kill-switch-capable feature flag system with percentage-based rollouts and audit logging.
3. Maintain an immutable, 2-year-retained audit trail of all state-changing admin actions.
4. Centralise all global platform configuration (fees, SLAs, limits) with versioned history and Redis caching.
5. Surface a human-readable admin roles and permissions matrix for access control governance.

## Scope

### In Scope
- Admin staff CRUD (invite, view, update role, suspend, remove)
- Feature flag CRUD with environment targeting and rollout percentage
- Append-only platform audit log with before/after JSON diffs
- Global platform configuration key-value management by domain
- Admin role and permission matrix read APIs

### Out of Scope
- Pharmacy staff management - EPIC-010
- Customer management - EPIC-002
- Rider management - EPIC-014
- Analytics dashboards - EPIC-019
- Notification templates - EPIC-015

## Stories

| Story ID | Title | Priority | Complexity |
|----------|-------|----------|------------|
| STORY-001 | Admin Staff Management | P0 | M |
| STORY-002 | Feature Flag Management | P0 | M |
| STORY-003 | Platform Audit Log | P0 | L |
| STORY-004 | Platform Configuration | P0 | M |
| STORY-005 | Admin RBAC Role-Permission Matrix | P0 | S |

## Success Metrics

- Zero admin accounts without MFA within 7 days of launch (admin_super)
- Audit log write latency ? 10 ms p99 (non-blocking middleware)
- Feature flag state propagation latency to frontend ? 60 seconds
- Config change reflection latency ? 60 seconds (Redis TTL flush)
- All admin_super actions attributable to a named actor in the audit log

## Dependencies

- EPIC-001 - Authentication & Identity (admin auth, RBAC middleware)
- EPIC-000 - Infrastructure (Redis for config/flag cache, email for staff invitations)
