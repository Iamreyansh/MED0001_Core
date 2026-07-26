# Agent Requirement Implementation Tracker

> Single source of truth for agent-driven story implementation status.
> Updated by the agent via `/next-story` (see `.cursor/rules/story-tracker.mdc`).

## How this file is used

- Stories are listed in **implementation order** (Phase → Epic → Story), per `INDEX.md`.
- The agent picks the **first `pending` row whose dependencies are `done`**, sets it `in_progress`, implements it, and only sets `done` after `make check` is green.
- Statuses: `pending` | `in_progress` | `done` | `blocked` (blocked rows carry the reason in Notes).
- Dates are `YYYY-MM-DD`. Notes record the target `domains/*` module and any deviations.

## Progress

| Phase | Total | Done |
|-------|-------|------|
| Phase 1 | 46 | 6 |
| Phase 2 | 34 | 0 |
| Phase 3 | 32 | 0 |
| Phase 4 | 17 | 0 |
| **Total** | **129** | **6** |

---

## Phase 1 — Foundation + Core Marketplace


### EPIC-001 (`EPIC-001-auth-identity`)

| Epic | Story | Title | Status | Completed | Notes |
|------|-------|-------|--------|-----------|-------|
| EPIC-001 | [STORY-001](./EPIC-001-auth-identity/STORY-001-customer-otp-auth.md) | Customer Mobile OTP Authentication | done | 2026-07-25 | `domains/auth`; SMS stub (`LoggingSmsSender`); minimal `customers`+`sessions` for verify; STORY-004 owns refresh lifecycle; magic OTP for `+919999900000`–`+919999900099` |
| EPIC-001 | [STORY-002](./EPIC-001-auth-identity/STORY-002-pharmacy-staff-auth.md) | Pharmacy Staff Authentication | done | 2026-07-26 | `domains/auth`; stub `pharmacies`+`pharmacy_roles` until EPIC-003/005; bcrypt cost 12 via `staffPasswordEncoder`; POS scope filter; login audit table |
| EPIC-001 | [STORY-003](./EPIC-001-auth-identity/STORY-003-admin-auth.md) | Admin Staff Authentication & MFA | done | 2026-07-26 | `domains/auth`; stub `admin_staff` until EPIC-021; TOTP via stdlib + AES-256-GCM (`AesGcmCipher`); MFA challenge scope filter; access 15m / refresh 8h |
| EPIC-001 | [STORY-004](./EPIC-001-auth-identity/STORY-004-token-management.md) | JWT Token Management & Session Control | done | 2026-07-26 | `domains/auth`; soft revoke via `revoked_at`; rotate creates new session row; admin `user_type`=`admin_staff` (legacy `admin` normalized); `is_current` always false under Bearer-only list; no GeoIP; POS/MFA filters unchanged; reuse revoke+`auth.refresh_token_reused` via `REQUIRES_NEW` + `JdbcOutboxStore`/`outbox_message` |
| EPIC-001 | [STORY-005](./EPIC-001-auth-identity/STORY-005-rbac-permissions.md) | Role-Based Access Control (RBAC) | done | 2026-07-26 | `domains/auth`; system pharmacy roles owner/manager/pharmacist/cashier/delivery; custom roles + soft-delete ROLE_IN_USE; admin matrix in code (`*:*` for admin_super); `RequiresPermission` interceptor; Redis role-permission cache; stub `POST /admin/pharmacies/{id}/suspend` for middleware AC until EPIC-004 |

### EPIC-002 (`EPIC-002-customer-management`)

| Epic | Story | Title | Status | Completed | Notes |
|------|-------|-------|--------|-----------|-------|
| EPIC-002 | [STORY-001](./EPIC-002-customer-management/STORY-001-customer-profile.md) | Customer Profile Management | done | 2026-07-26 | `domains/customer`; V006 flags/deletion/stats + city index; list order default desc; compliance blocked on GET detail; notify DB-locked 3/24h, `delivered=false`, outbox without phone; anonymise wipes city + 60-hex hash; nightly `@Scheduled` Asia/Kolkata; avatar CDN allowlist; wallet/loyalty stub until STORY-003/005; ActiveOrdersPort stub; FCM/SMS consumer EPIC-017 |
| EPIC-002 | [STORY-002](./EPIC-002-customer-management/STORY-002-address-management.md) | Delivery Address Management | pending | — | — |
| EPIC-002 | [STORY-003](./EPIC-002-customer-management/STORY-003-wallet-management.md) | Namma Money Wallet | pending | — | — |
| EPIC-002 | [STORY-004](./EPIC-002-customer-management/STORY-004-saved-payment-methods.md) | Saved Payment Methods | pending | — | — |
| EPIC-002 | [STORY-005](./EPIC-002-customer-management/STORY-005-loyalty-referrals.md) | Loyalty Points & Referral Programme | pending | — | — |

### EPIC-003 (`EPIC-003-pharmacy-onboarding-kyc`)

| Epic | Story | Title | Status | Completed | Notes |
|------|-------|-------|--------|-----------|-------|
| EPIC-003 | [STORY-001](./EPIC-003-pharmacy-onboarding-kyc/STORY-001-pharmacy-registration.md) | Pharmacy Registration | pending | — | — |
| EPIC-003 | [STORY-002](./EPIC-003-pharmacy-onboarding-kyc/STORY-002-kyc-document-upload.md) | KYC Document Upload | pending | — | — |
| EPIC-003 | [STORY-003](./EPIC-003-pharmacy-onboarding-kyc/STORY-003-auto-kyc-verification.md) | Auto KYC Verification | pending | — | — |
| EPIC-003 | [STORY-004](./EPIC-003-pharmacy-onboarding-kyc/STORY-004-kyc-status-management.md) | KYC Status Management (Admin) | pending | — | — |
| EPIC-003 | [STORY-005](./EPIC-003-pharmacy-onboarding-kyc/STORY-005-pharmacy-profile-update.md) | Pharmacy Profile Update | pending | — | — |

### EPIC-004 (`EPIC-004-pharmacy-operations-admin`)

| Epic | Story | Title | Status | Completed | Notes |
|------|-------|-------|--------|-----------|-------|
| EPIC-004 | [STORY-001](./EPIC-004-pharmacy-operations-admin/STORY-001-pharmacy-directory.md) | Pharmacy Directory | pending | — | — |
| EPIC-004 | [STORY-002](./EPIC-004-pharmacy-operations-admin/STORY-002-pharmacy-performance-metrics.md) | Pharmacy Performance Metrics | pending | — | — |
| EPIC-004 | [STORY-003](./EPIC-004-pharmacy-operations-admin/STORY-003-commission-payout-management.md) | Commission & Payout Management | pending | — | — |
| EPIC-004 | [STORY-004](./EPIC-004-pharmacy-operations-admin/STORY-004-storefront-zone-control.md) | Storefront & Zone Control | pending | — | — |
| EPIC-004 | [STORY-005](./EPIC-004-pharmacy-operations-admin/STORY-005-admin-pharmacy-actions.md) | Admin Pharmacy Actions | pending | — | — |

### EPIC-005 (`EPIC-005-master-catalogue`)

| Epic | Story | Title | Status | Completed | Notes |
|------|-------|-------|--------|-----------|-------|
| EPIC-005 | [STORY-001](./EPIC-005-master-catalogue/STORY-001-medicine-master-crud.md) | Medicine Master CRUD | pending | — | — |
| EPIC-005 | [STORY-002](./EPIC-005-master-catalogue/STORY-002-category-schedule-management.md) | Category & Schedule Management | pending | — | — |
| EPIC-005 | [STORY-003](./EPIC-005-master-catalogue/STORY-003-medicine-search-discovery.md) | Medicine Search & Discovery | pending | — | — |
| EPIC-005 | [STORY-004](./EPIC-005-master-catalogue/STORY-004-price-ceiling-management.md) | Price Ceiling Management | pending | — | — |
| EPIC-005 | [STORY-005](./EPIC-005-master-catalogue/STORY-005-pharmacy-catalogue-mapping.md) | Pharmacy Catalogue Mapping | pending | — | — |

### EPIC-010 (`EPIC-010-order-management`)

| Epic | Story | Title | Status | Completed | Notes |
|------|-------|-------|--------|-----------|-------|
| EPIC-010 | [STORY-001](./EPIC-010-order-management/STORY-001-cart-management.md) | Customer Cart Lifecycle | pending | — | — |
| EPIC-010 | [STORY-002](./EPIC-010-order-management/STORY-002-smart-pharmacy-selection.md) | Smart Pharmacy Auto-Selection Engine | pending | — | — |
| EPIC-010 | [STORY-003](./EPIC-010-order-management/STORY-003-rx-quote-broadcast.md) | Prescription Quote Broadcast to Pharmacies | pending | — | — |
| EPIC-010 | [STORY-004](./EPIC-010-order-management/STORY-004-order-placement.md) | Order Placement and Payment | pending | — | — |
| EPIC-010 | [STORY-005](./EPIC-010-order-management/STORY-005-order-status-lifecycle.md) | Order Status Lifecycle | pending | — | — |
| EPIC-010 | [STORY-006](./EPIC-010-order-management/STORY-006-order-cancellation-refund.md) | Order Cancellation and Refund | pending | — | — |
| EPIC-010 | [STORY-007](./EPIC-010-order-management/STORY-007-reorder.md) | Customer Reorder | pending | — | — |
| EPIC-010 | [STORY-008](./EPIC-010-order-management/STORY-008-admin-order-management.md) | Admin Order Management and Oversight | pending | — | — |

### EPIC-011 (`EPIC-011-rider-delivery`)

| Epic | Story | Title | Status | Completed | Notes |
|------|-------|-------|--------|-----------|-------|
| EPIC-011 | [STORY-001](./EPIC-011-rider-delivery/STORY-001-rider-onboarding-kyc.md) | Rider Onboarding & KYC | pending | — | — |
| EPIC-011 | [STORY-002](./EPIC-011-rider-delivery/STORY-002-rider-status-shift.md) | Rider Availability & Shift Management | pending | — | — |
| EPIC-011 | [STORY-003](./EPIC-011-rider-delivery/STORY-003-order-assignment-engine.md) | Order Assignment Engine | pending | — | — |
| EPIC-011 | [STORY-004](./EPIC-011-rider-delivery/STORY-004-realtime-rider-tracking.md) | Real-Time Rider GPS Tracking | pending | — | — |
| EPIC-011 | [STORY-005](./EPIC-011-rider-delivery/STORY-005-delivery-zone-management.md) | Delivery Zone Management | pending | — | — |
| EPIC-011 | [STORY-006](./EPIC-011-rider-delivery/STORY-006-delivery-pricing.md) | Delivery Fee Pricing | pending | — | — |
| EPIC-011 | [STORY-007](./EPIC-011-rider-delivery/STORY-007-cod-reconciliation.md) | COD Reconciliation (Rider Side) | pending | — | — |
| EPIC-011 | [STORY-008](./EPIC-011-rider-delivery/STORY-008-rider-incentives-performance.md) | Rider Incentives & Performance | pending | — | — |

### EPIC-021 (`EPIC-021-settings-admin`)

| Epic | Story | Title | Status | Completed | Notes |
|------|-------|-------|--------|-----------|-------|
| EPIC-021 | [STORY-001](./EPIC-021-settings-admin/STORY-001-admin-staff-management.md) | Admin Staff Management | pending | — | — |
| EPIC-021 | [STORY-002](./EPIC-021-settings-admin/STORY-002-feature-flags.md) | Feature Flag Management | pending | — | — |
| EPIC-021 | [STORY-003](./EPIC-021-settings-admin/STORY-003-platform-audit-log.md) | Platform Audit Log | pending | — | — |
| EPIC-021 | [STORY-004](./EPIC-021-settings-admin/STORY-004-platform-configuration.md) | Platform Configuration | pending | — | — |
| EPIC-021 | [STORY-005](./EPIC-021-settings-admin/STORY-005-rbac-admin-roles.md) | Admin RBAC Role-Permission Matrix | pending | — | — |

---

## Phase 2 — Finance + Pharmacy ERP


### EPIC-006 (`EPIC-006-pharmacy-inventory`)

| Epic | Story | Title | Status | Completed | Notes |
|------|-------|-------|--------|-----------|-------|
| EPIC-006 | [STORY-001](./EPIC-006-pharmacy-inventory/STORY-001-inventory-management.md) | Inventory Management - Stock Master, Product CRUD & Visibility Controls | pending | — | — |
| EPIC-006 | [STORY-002](./EPIC-006-pharmacy-inventory/STORY-002-batch-expiry-management.md) | Batch & Expiry Management - FEFO Tracking and Expiry Alerts | pending | — | — |
| EPIC-006 | [STORY-003](./EPIC-006-pharmacy-inventory/STORY-003-rack-location-management.md) | Rack Location Management - Physical Shelf Mapping | pending | — | — |
| EPIC-006 | [STORY-004](./EPIC-006-pharmacy-inventory/STORY-004-purchase-grn-management.md) | Purchase / GRN Management - Distributor Invoice Entry & Goods Received Notes | pending | — | — |
| EPIC-006 | [STORY-005](./EPIC-006-pharmacy-inventory/STORY-005-distributor-management.md) | Distributor Management - Supplier Directory & Price Comparison | pending | — | — |
| EPIC-006 | [STORY-006](./EPIC-006-pharmacy-inventory/STORY-006-reorder-suggestions.md) | Reorder Suggestions - Auto-Reorder Intelligence | pending | — | — |

### EPIC-007 (`EPIC-007-pharmacy-pos-billing`)

| Epic | Story | Title | Status | Completed | Notes |
|------|-------|-------|--------|-----------|-------|
| EPIC-007 | [STORY-001](./EPIC-007-pharmacy-pos-billing/STORY-001-counter-sale-pos.md) | Counter Sale POS - Real-Time Cart and Checkout | pending | — | — |
| EPIC-007 | [STORY-002](./EPIC-007-pharmacy-pos-billing/STORY-002-gst-invoice-management.md) | GST Invoice Management - Generation, Customization & Sharing | pending | — | — |
| EPIC-007 | [STORY-003](./EPIC-007-pharmacy-pos-billing/STORY-003-credit-khata-management.md) | Credit / Khata Management - Customer Credit Tracking | pending | — | — |
| EPIC-007 | [STORY-004](./EPIC-007-pharmacy-pos-billing/STORY-004-sales-ledger.md) | Sales Ledger - Complete Sales Audit Trail | pending | — | — |
| EPIC-007 | [STORY-005](./EPIC-007-pharmacy-pos-billing/STORY-005-pharmacy-offers-discounts.md) | Pharmacy Offers & Discounts - Promotions and Coupon Engine | pending | — | — |

### EPIC-012 (`EPIC-012-payments-finance`)

| Epic | Story | Title | Status | Completed | Notes |
|------|-------|-------|--------|-----------|-------|
| EPIC-012 | [STORY-001](./EPIC-012-payments-finance/STORY-001-payment-processing.md) | Payment Processing (UPI / Card / COD) | pending | — | — |
| EPIC-012 | [STORY-002](./EPIC-012-payments-finance/STORY-002-wallet-operations.md) | Namma Money Wallet Operations | pending | — | — |
| EPIC-012 | [STORY-003](./EPIC-012-payments-finance/STORY-003-pharmacy-settlements.md) | Pharmacy Settlements | pending | — | — |
| EPIC-012 | [STORY-004](./EPIC-012-payments-finance/STORY-004-rider-payouts.md) | Rider Payouts | pending | — | — |
| EPIC-012 | [STORY-005](./EPIC-012-payments-finance/STORY-005-refund-processing.md) | Refund Processing | pending | — | — |
| EPIC-012 | [STORY-006](./EPIC-012-payments-finance/STORY-006-cod-float-management.md) | COD Float Management (Finance Side) | pending | — | — |
| EPIC-012 | [STORY-007](./EPIC-012-payments-finance/STORY-007-tax-gst-management.md) | Tax & GST Management | pending | — | — |
| EPIC-012 | [STORY-008](./EPIC-012-payments-finance/STORY-008-financial-ledger.md) | Financial Ledger | pending | — | — |
| EPIC-012 | [STORY-009](./EPIC-012-payments-finance/STORY-009-financial-overview-dashboard.md) | Financial Overview Dashboard | pending | — | — |

### EPIC-014 (`EPIC-014-crm-saas`)

| Epic | Story | Title | Status | Completed | Notes |
|------|-------|-------|--------|-----------|-------|
| EPIC-014 | [STORY-001](./EPIC-014-crm-saas/STORY-001-saas-plan-management.md) | SaaS Plan Management | pending | — | — |
| EPIC-014 | [STORY-002](./EPIC-014-crm-saas/STORY-002-subscription-management.md) | Subscription Management | pending | — | — |
| EPIC-014 | [STORY-003](./EPIC-014-crm-saas/STORY-003-saas-billing-invoicing.md) | SaaS Billing and Invoicing | pending | — | — |
| EPIC-014 | [STORY-004](./EPIC-014-crm-saas/STORY-004-lead-pipeline.md) | Lead Pipeline | pending | — | — |
| EPIC-014 | [STORY-005](./EPIC-014-crm-saas/STORY-005-account-health-scoring.md) | Account Health Scoring | pending | — | — |
| EPIC-014 | [STORY-006](./EPIC-014-crm-saas/STORY-006-feature-adoption-metering.md) | Feature Adoption Metering | pending | — | — |
| EPIC-014 | [STORY-007](./EPIC-014-crm-saas/STORY-007-renewal-churn-management.md) | Renewal and Churn Management | pending | — | — |
| EPIC-014 | [STORY-008](./EPIC-014-crm-saas/STORY-008-saas-revenue-analytics.md) | SaaS Revenue Analytics | pending | — | — |

### EPIC-022 (`EPIC-022-external-integrations`)

| Epic | Story | Title | Status | Completed | Notes |
|------|-------|-------|--------|-----------|-------|
| EPIC-022 | [STORY-001](./EPIC-022-external-integrations/STORY-001-razorpay-integration.md) | Razorpay Integration | pending | — | — |
| EPIC-022 | [STORY-002](./EPIC-022-external-integrations/STORY-002-maps-geolocation.md) | Maps & Geolocation | pending | — | — |
| EPIC-022 | [STORY-003](./EPIC-022-external-integrations/STORY-003-government-api-integration.md) | Government API Integration | pending | — | — |
| EPIC-022 | [STORY-004](./EPIC-022-external-integrations/STORY-004-einvoicing-irn.md) | E-Invoicing IRN | pending | — | — |
| EPIC-022 | [STORY-005](./EPIC-022-external-integrations/STORY-005-accounting-integration.md) | Accounting Integration | pending | — | — |
| EPIC-022 | [STORY-006](./EPIC-022-external-integrations/STORY-006-communication-integrations.md) | Communication Integrations | pending | — | — |

---

## Phase 3 — Prescriptions + Growth


### EPIC-008 (`EPIC-008-prescription-management`)

| Epic | Story | Title | Status | Completed | Notes |
|------|-------|-------|--------|-----------|-------|
| EPIC-008 | [STORY-001](./EPIC-008-prescription-management/STORY-001-prescription-upload.md) | Prescription Upload and Storage | pending | — | — |
| EPIC-008 | [STORY-002](./EPIC-008-prescription-management/STORY-002-pharmacy-rx-queue.md) | Pharmacy Prescription Review and Dispense Workflow | pending | — | — |
| EPIC-008 | [STORY-003](./EPIC-008-prescription-management/STORY-003-rx-compliance-audit.md) | Admin Rx Compliance Audit | pending | — | — |
| EPIC-008 | [STORY-004](./EPIC-008-prescription-management/STORY-004-schedule-drug-register.md) | Statutory Schedule H1/X Drug Register | pending | — | — |
| EPIC-008 | [STORY-005](./EPIC-008-prescription-management/STORY-005-doctor-registry.md) | Prescribing Doctor Registry and Verification | pending | — | — |
| EPIC-008 | [STORY-006](./EPIC-008-prescription-management/STORY-006-compliance-reports-filings.md) | Regulatory Compliance Filings and Reports | pending | — | — |

### EPIC-009 (`EPIC-009-doctor-teleconsult`)

| Epic | Story | Title | Status | Completed | Notes |
|------|-------|-------|--------|-----------|-------|
| EPIC-009 | [STORY-001](./EPIC-009-doctor-teleconsult/STORY-001-doctor-profile-management.md) | Teleconsult Doctor Profile Management | pending | — | — |
| EPIC-009 | [STORY-002](./EPIC-009-doctor-teleconsult/STORY-002-consult-request-scheduling.md) | Patient Consultation Request and Scheduling | pending | — | — |
| EPIC-009 | [STORY-003](./EPIC-009-doctor-teleconsult/STORY-003-consult-session-management.md) | Teleconsult Session Lifecycle Management | pending | — | — |
| EPIC-009 | [STORY-004](./EPIC-009-doctor-teleconsult/STORY-004-eprescription-generation.md) | e-Prescription Generation and Linking | pending | — | — |

### EPIC-013 (`EPIC-013-marketing-growth`)

| Epic | Story | Title | Status | Completed | Notes |
|------|-------|-------|--------|-----------|-------|
| EPIC-013 | [STORY-001](./EPIC-013-marketing-growth/STORY-001-coupon-management.md) | Coupon Management | pending | — | — |
| EPIC-013 | [STORY-002](./EPIC-013-marketing-growth/STORY-002-banner-cms-management.md) | Banner CMS Management | pending | — | — |
| EPIC-013 | [STORY-003](./EPIC-013-marketing-growth/STORY-003-campaign-management.md) | Campaign Management | pending | — | — |
| EPIC-013 | [STORY-004](./EPIC-013-marketing-growth/STORY-004-customer-segmentation.md) | Customer Segmentation | pending | — | — |
| EPIC-013 | [STORY-005](./EPIC-013-marketing-growth/STORY-005-referral-program.md) | Referral Program | pending | — | — |
| EPIC-013 | [STORY-006](./EPIC-013-marketing-growth/STORY-006-loyalty-program.md) | Loyalty Program | pending | — | — |

### EPIC-015 (`EPIC-015-support-disputes`)

| Epic | Story | Title | Status | Completed | Notes |
|------|-------|-------|--------|-----------|-------|
| EPIC-015 | [STORY-001](./EPIC-015-support-disputes/STORY-001-support-ticket-management.md) | Support Ticket Management | pending | — | — |
| EPIC-015 | [STORY-002](./EPIC-015-support-disputes/STORY-002-dispute-management.md) | Dispute Management | pending | — | — |
| EPIC-015 | [STORY-003](./EPIC-015-support-disputes/STORY-003-sla-escalation-management.md) | SLA and Escalation Management | pending | — | — |
| EPIC-015 | [STORY-004](./EPIC-015-support-disputes/STORY-004-agent-management.md) | Agent Management | pending | — | — |
| EPIC-015 | [STORY-005](./EPIC-015-support-disputes/STORY-005-knowledge-base.md) | Knowledge Base | pending | — | — |

### EPIC-017 (`EPIC-017-notifications-comms`)

| Epic | Story | Title | Status | Completed | Notes |
|------|-------|-------|--------|-----------|-------|
| EPIC-017 | [STORY-001](./EPIC-017-notifications-comms/STORY-001-push-notification-service.md) | Push Notification Service | pending | — | — |
| EPIC-017 | [STORY-002](./EPIC-017-notifications-comms/STORY-002-sms-service.md) | SMS Service | pending | — | — |
| EPIC-017 | [STORY-003](./EPIC-017-notifications-comms/STORY-003-whatsapp-business-api.md) | WhatsApp Business API | pending | — | — |
| EPIC-017 | [STORY-004](./EPIC-017-notifications-comms/STORY-004-email-service.md) | Email Service | pending | — | — |
| EPIC-017 | [STORY-005](./EPIC-017-notifications-comms/STORY-005-notification-preferences.md) | Notification Preferences | pending | — | — |
| EPIC-017 | [STORY-006](./EPIC-017-notifications-comms/STORY-006-notification-history.md) | Notification History | pending | — | — |

### EPIC-018 (`EPIC-018-medicine-schedule`)

| Epic | Story | Title | Status | Completed | Notes |
|------|-------|-------|--------|-----------|-------|
| EPIC-018 | [STORY-001](./EPIC-018-medicine-schedule/STORY-001-medicine-schedule-crud.md) | Medicine Schedule CRUD - Add, Manage & Remove Medicines | pending | — | — |
| EPIC-018 | [STORY-002](./EPIC-018-medicine-schedule/STORY-002-care-circle-management.md) | Care Circle Management - Family Member Scheduling | pending | — | — |
| EPIC-018 | [STORY-003](./EPIC-018-medicine-schedule/STORY-003-dose-reminder-engine.md) | Dose Reminder Engine - Push Notification Scheduling & Delivery | pending | — | — |
| EPIC-018 | [STORY-004](./EPIC-018-medicine-schedule/STORY-004-adherence-tracking.md) | Adherence Tracking - Stats, Calendar, and Streaks | pending | — | — |
| EPIC-018 | [STORY-005](./EPIC-018-medicine-schedule/STORY-005-refill-alerts.md) | Refill Alerts - Supply Tracking and Reorder | pending | — | — |

---

## Phase 4 — Intelligence + Automation


### EPIC-016 (`EPIC-016-analytics-reporting`)

| Epic | Story | Title | Status | Completed | Notes |
|------|-------|-------|--------|-----------|-------|
| EPIC-016 | [STORY-001](./EPIC-016-analytics-reporting/STORY-001-platform-overview-analytics.md) | Platform Overview Analytics | pending | — | — |
| EPIC-016 | [STORY-002](./EPIC-016-analytics-reporting/STORY-002-operations-sla-analytics.md) | Operations & SLA Analytics | pending | — | — |
| EPIC-016 | [STORY-003](./EPIC-016-analytics-reporting/STORY-003-growth-cohort-analytics.md) | Growth & Cohort Analytics | pending | — | — |
| EPIC-016 | [STORY-004](./EPIC-016-analytics-reporting/STORY-004-pharmacy-analytics.md) | Pharmacy Analytics | pending | — | — |
| EPIC-016 | [STORY-005](./EPIC-016-analytics-reporting/STORY-005-geography-analytics.md) | Geography Analytics | pending | — | — |
| EPIC-016 | [STORY-006](./EPIC-016-analytics-reporting/STORY-006-report-library.md) | Report Library | pending | — | — |

### EPIC-019 (`EPIC-019-automation-rules-engine`)

| Epic | Story | Title | Status | Completed | Notes |
|------|-------|-------|--------|-----------|-------|
| EPIC-019 | [STORY-001](./EPIC-019-automation-rules-engine/STORY-001-rules-engine-core.md) | Rules Engine Core | pending | — | — |
| EPIC-019 | [STORY-002](./EPIC-019-automation-rules-engine/STORY-002-rule-crud-management.md) | Rule CRUD Management | pending | — | — |
| EPIC-019 | [STORY-003](./EPIC-019-automation-rules-engine/STORY-003-workflow-journey-builder.md) | Workflow / Journey Builder | pending | — | — |
| EPIC-019 | [STORY-004](./EPIC-019-automation-rules-engine/STORY-004-rule-simulation.md) | Rule Simulation | pending | — | — |
| EPIC-019 | [STORY-005](./EPIC-019-automation-rules-engine/STORY-005-activity-log-audit.md) | Activity Log & Audit | pending | — | — |
| EPIC-019 | [STORY-006](./EPIC-019-automation-rules-engine/STORY-006-approvals-queue.md) | Approvals Queue | pending | — | — |
| EPIC-019 | [STORY-007](./EPIC-019-automation-rules-engine/STORY-007-automation-health-killswitch.md) | Automation Health & Kill Switch | pending | — | — |
| EPIC-019 | [STORY-008](./EPIC-019-automation-rules-engine/STORY-008-seed-automations.md) | Seed Automations | pending | — | — |

### EPIC-020 (`EPIC-020-observability-self-healing`)

| Epic | Story | Title | Status | Completed | Notes |
|------|-------|-------|--------|-----------|-------|
| EPIC-020 | [STORY-001](./EPIC-020-observability-self-healing/STORY-001-realtime-monitoring-alerting.md) | Real-Time Monitoring & Alerting | pending | — | — |
| EPIC-020 | [STORY-002](./EPIC-020-observability-self-healing/STORY-002-auto-remediation.md) | Auto-Remediation | pending | — | — |
| EPIC-020 | [STORY-003](./EPIC-020-observability-self-healing/STORY-003-slo-incident-management.md) | SLO & Incident Management | pending | — | — |
