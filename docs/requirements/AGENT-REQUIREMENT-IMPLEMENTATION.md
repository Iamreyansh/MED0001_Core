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
| Phase 1 | 46 | 46 |
| Phase 2 | 34 | 34 |
| Phase 3 | 32 | 10 |
| Phase 4 | 17 | 0 |
| **Total** | **129** | **84** |

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
| EPIC-002 | [STORY-002](./EPIC-002-customer-management/STORY-002-address-management.md) | Delivery Address Management | done | 2026-07-26 | `domains/customer`; V007 addresses + `default_address_id`; max 10; first auto-default; atomic set-default; soft delete; `AddressInActiveOrderPort` stub until EPIC-010; geocode stub + Redis 1h cache@4dp, Google when `medmate.maps.geocode.api-key` set; no admin address API in story |
| EPIC-002 | [STORY-003](./EPIC-002-customer-management/STORY-003-wallet-management.md) | Namma Money Wallet | done | 2026-07-26 | `domains/customer`; V008 wallets+ledger (paise) + trigger auto-create; admin credit `finance:*` max 100000 paise; FIFO remaining_paise + nightly expiry Asia/Kolkata; checkout debit via `WalletService.debitForOrder` until EPIC-010; syncs `customers.wallet_balance_paise` |
| EPIC-002 | [STORY-004](./EPIC-002-customer-management/STORY-004-saved-payment-methods.md) | Saved Payment Methods | done | 2026-07-26 | `domains/customer`; V009+V010 saved_payment_methods (+idempotency_key); dedicated `paymentMethodCipher`; StubRazorpayVpaClient (+ live / SM `MEDMATE_SECRETS_RAZORPAY_ARN`); admin `GET …/payment-methods` (`customers:read`); VPA validate outside TX; softDelete scoped by customer_id; PaymentMethodInActiveOrderPort stub until EPIC-010; max 5 UPI + 5 cards; COD not persisted |
| EPIC-002 | [STORY-005](./EPIC-002-customer-management/STORY-005-loyalty-referrals.md) | Loyalty Points & Referral Programme | done | 2026-07-26 | `domains/customer`; V011 customer_loyalty + loyalty_transactions + customer_referrals + referral_events; DB trigger MED+4 codes; tiers NONE/SILVER/GOLD/PLATINUM on lifetime; wallet REFERRAL via systemCredit; order award/reverse + referral settle ports until EPIC-010; outbox `customer.loyalty.tier_changed` |

### EPIC-003 (`EPIC-003-pharmacy-onboarding-kyc`)

| Epic | Story | Title | Status | Completed | Notes |
|------|-------|-------|--------|-----------|-------|
| EPIC-003 | [STORY-001](./EPIC-003-pharmacy-onboarding-kyc/STORY-001-pharmacy-registration.md) | Pharmacy Registration | done | 2026-07-26 | `domains/pharmacy`; V012 widen `pharmacies` + OTP/pincode/audit; owner via `pharmacy_staff` + system `owner` role; magic OTP `*@nammamedmate.test`→`123456`; email uniqueness excludes `customers` (no email col); phone excludes `admin_staff`; password on `pharmacy_staff` (+ optional `pharmacies.password_hash`); partial pincode seed (ponytail) |
| EPIC-003 | [STORY-002](./EPIC-003-pharmacy-onboarding-kyc/STORY-002-kyc-document-upload.md) | KYC Document Upload | done | 2026-07-27 | `domains/pharmacy`; V013; multipart (story waiver) + local/S3 store; GuardDuty Malware Protection for S3 (`kyc/`) + EventBridge→SQS→worker soft-delete; deferred request-path scanner; rate limits; `admin_compliance`+`pharmacies:update`; servlet ≤10MB; magic-byte MIME; flag `medmate.kyc.auto-verification-enabled`; admin audit at URL issuance; auto_kyc_result null until STORY-003 |
| EPIC-003 | [STORY-003](./EPIC-003-pharmacy-onboarding-kyc/STORY-003-auto-kyc-verification.md) | Auto KYC Verification | done | — | — |
| EPIC-003 | [STORY-004](./EPIC-003-pharmacy-onboarding-kyc/STORY-004-kyc-status-management.md) | KYC Status Management (Admin) | done | 2026-07-28 | `domains/pharmacy`; V015 audit_log + stub zones + pharmacy code/SLA/suspend metadata; AdminPharmacyController list/detail/approve/reject/suspend/reactivate/request-documents; replaced auth suspend stub; outbox notify stubs; RBAC ops+suspend, support/finance+read; REJECTED status (legacy KYC_REJECTED still gated); commission default 8.00; list meta INDEX `has_next`; BR6 reactivate restores `can_reapply` only for `admin_super` |
| EPIC-003 | [STORY-005](./EPIC-003-pharmacy-onboarding-kyc/STORY-005-pharmacy-profile-update.md) | Pharmacy Profile Update | done | 2026-07-28 | `domains/pharmacy`; V016 hours/bank/change-requests/OTPs + profile tax flags; PharmacyProfileController + AdminPharmacyProfileController; stub penny-drop + magic OTP; bankAccountCipher; completeness N=13 (BR6); logo/IFSC format-only; no Redis hours cache; verify-contact endpoint; OTP outbox ids-only + ProfileContactNotifier; bank GET owner-only / admin finance+super via admin path; soft-delete bank re-entry |

### EPIC-004 (`EPIC-004-pharmacy-operations-admin`)

| Epic | Story | Title | Status | Completed | Notes |
|------|-------|-------|--------|-----------|-------|
| EPIC-004 | [STORY-001](./EPIC-004-pharmacy-operations-admin/STORY-001-pharmacy-directory.md) | Pharmacy Directory | done | 2026-08-08 | `domains/pharmacy`; V017 directory metrics + view; Redis summary 5m; OrderMetrics/CatalogueStats stubs; export CSV; support/compliance omit commission fields incl. ledger |
| EPIC-004 | [STORY-002](./EPIC-004-pharmacy-operations-admin/STORY-002-pharmacy-performance-metrics.md) | Pharmacy Performance Metrics | done | 2026-08-08 | `domains/pharmacy`; V018 snapshots+alerts; aggregator 02:00 IST; NotificationDispatchPort stub/outbox; OrderMetrics ratings/orders; compliance performance view fill+OOS only |
| EPIC-004 | [STORY-003](./EPIC-004-pharmacy-operations-admin/STORY-003-commission-payout-management.md) | Commission & Payout Management | done | 2026-08-08 | `domains/pharmacy`; V019 commission_history+settlement; RazorpayX stub+HMAC webhook; Idempotency-Key required on release; TCS; IST schedulers; finance:update/settlements:process RBAC |
| EPIC-004 | [STORY-004](./EPIC-004-pharmacy-operations-admin/STORY-004-storefront-zone-control.md) | Storefront & Zone Control | done | 2026-08-08 | `domains/pharmacy`; V020 admin_forced_offline+catalogue_pause+zones; ZonePharmacyCachePort Redis; CatalogueVisibilityPort stub; owner storefront; PHARMACY_OFFLINE order check deferred EPIC-010 |
| EPIC-004 | [STORY-005](./EPIC-004-pharmacy-operations-admin/STORY-005-admin-pharmacy-actions.md) | Admin Pharmacy Actions | done | 2026-08-08 | `domains/pharmacy`; V021 notices/notes/call_log/bulk_jobs; NotificationDispatchPort outbox; WA template registry; bulk ponytail in-process (upgrade→SQS); pharmacies:read gate + service RBAC; bulk skips per-pharmacy notice limit |

### EPIC-005 (`EPIC-005-master-catalogue`)

| Epic | Story | Title | Status | Completed | Notes |
|------|-------|-------|--------|-----------|-------|
| EPIC-005 | [STORY-001](./EPIC-005-master-catalogue/STORY-001-medicine-master-crud.md) | Medicine Master CRUD | done | 2026-08-08 | `domains/catalogue`; V023+V026 ban job; stocking_pharmacies from mappings; BanMappingHidePort wired; demand stub until EPIC-010; compliance create forbidden / schedule-only update |
| EPIC-005 | [STORY-002](./EPIC-005-master-catalogue/STORY-002-category-schedule-management.md) | Category & Schedule Management | done | 2026-08-08 | `domains/catalogue`; V022 medicine_category + seed; Redis 5m; include_deleted+is_deleted; online_delivery_allowed on all schedules |
| EPIC-005 | [STORY-003](./EPIC-005-master-catalogue/STORY-003-medicine-search-discovery.md) | Medicine Search & Discovery | done | 2026-08-08 | `domains/catalogue`; Redis AC+detail; show_oos + admin include_banned; CUSTOM SKU empty until EPIC-006; lat/lng zone geometry deferred EPIC-011/010 |
| EPIC-005 | [STORY-004](./EPIC-005-master-catalogue/STORY-004-price-ceiling-management.md) | Price Ceiling Management | done | 2026-08-08 | `domains/catalogue`; V025 violations; outbox notify; nightly detector; PriceCeilingGuard for EPIC-010 |
| EPIC-005 | [STORY-005](./EPIC-005-master-catalogue/STORY-005-pharmacy-catalogue-mapping.md) | Pharmacy Catalogue Mapping | done | 2026-08-08 | `domains/catalogue`; V024 mappings; BanMappingHidePort real; apps/api bridges CatalogueVisibilityPort+PharmacyCatalogueStatsPort; BULK_MAP on bulk_action_job |

### EPIC-010 (`EPIC-010-order-management`)

| Epic | Story | Title | Status | Completed | Notes |
|------|-------|-------|--------|-----------|-------|
| EPIC-010 | [STORY-001](./EPIC-010-order-management/STORY-001-cart-management.md) | Customer Cart Lifecycle | done | 2026-08-08 | `domains/order`; CartPricing paise; smart-select on first add; 24h abandon; address/Rx/wallet stubs |
| EPIC-010 | [STORY-002](./EPIC-010-order-management/STORY-002-smart-pharmacy-selection.md) | Smart Pharmacy Auto-Selection Engine | done | 2026-08-08 | `domains/order`; Haversine; multiplicative score; inventory stub until EPIC-006; lat/lng on pharmacies |
| EPIC-010 | [STORY-003](./EPIC-010-order-management/STORY-003-rx-quote-broadcast.md) | Prescription Quote Broadcast to Pharmacies | done | 2026-08-08 | `domains/order`; 3km/10; select abandons prior cart; outbox notify; Rx stub |
| EPIC-010 | [STORY-004](./EPIC-010-order-management/STORY-004-order-placement.md) | Order Placement and Payment | done | 2026-08-08 | `domains/order`; Razorpay stub; wallet bridge; Idempotency-Key; inventory/Rx stubs |
| EPIC-010 | [STORY-005](./EPIC-010-order-management/STORY-005-order-status-lifecycle.md) | Order Status Lifecycle | done | 2026-08-08 | `domains/order`; state machine; OTP hash; 10m timeout cancel; 30m rider alert-only; SLA |
| EPIC-010 | [STORY-006](./EPIC-010-order-management/STORY-006-order-cancellation-refund.md) | Order Cancellation and Refund | done | 2026-08-08 | `domains/order`; refund routing; Razorpay refund stub+webhook; no-rider auto-cancel deferred EPIC-019; wallet credit bridge |
| EPIC-010 | [STORY-007](./EPIC-010-order-management/STORY-007-reorder.md) | Customer Reorder | done | 2026-08-08 | `domains/order`; no Rx/coupon reattach; abandon prior cart; smart-select fallback |
| EPIC-010 | [STORY-008](./EPIC-010-order-management/STORY-008-admin-order-management.md) | Admin Order Management and Oversight | done | 2026-08-08 | `domains/order`; live-feed Redis 10s; async CSV export; Rx redaction; commission display-only |

### EPIC-011 (`EPIC-011-rider-delivery`)

| Epic | Story | Title | Status | Completed | Notes |
|------|-------|-------|--------|-----------|-------|
| EPIC-011 | [STORY-001](./EPIC-011-rider-delivery/STORY-001-rider-onboarding-kyc.md) | Rider Onboarding & KYC | done | 2026-08-08 | `domains/rider`; V035; rider OTP auth; KYC under private `kyc/riders/`; multipart story waiver (pharmacy-style); Aadhaar stub+flag; OrderRiderBridgeConfig; session revoke on block |
| EPIC-011 | [STORY-002](./EPIC-011-rider-delivery/STORY-002-rider-status-shift.md) | Rider Availability & Shift Management | done | 2026-08-08 | `domains/rider`; V036 shifts+audit; Redis status; ActiveDeliveryPort bridge; OFFLINE_DURING_DELIVERY 200+warning; fleet limit default 20 |
| EPIC-011 | [STORY-003](./EPIC-011-rider-delivery/STORY-003-order-assignment-engine.md) | Order Assignment Engine | done | 2026-08-08 | `domains/rider`; V037 assignments+earnings stub; Redis OTP; timeout requeue; pickup OTP pharmacy-only; meta.unassigned_total; distance stub |
| EPIC-011 | [STORY-004](./EPIC-011-rider-delivery/STORY-004-realtime-rider-tracking.md) | Real-Time Rider GPS Tracking | done | 2026-08-08 | `domains/rider`; V038 PostGIS+locations+geofences; Redis 5m; SSE afterCommit; 30d purge; Bruno stream |
| EPIC-011 | [STORY-005](./EPIC-011-rider-delivery/STORY-005-delivery-zone-management.md) | Delivery Zone Management | done | 2026-08-08 | `domains/rider`; V039 zones polygon+fees+surge; rebalancing stub; OrderZoneBridgeConfig ZoneMembershipPort; list limit default 20 |
| EPIC-011 | [STORY-006](./EPIC-011-rider-delivery/STORY-006-delivery-pricing.md) | Delivery Fee Pricing | done | 2026-08-08 | `domains/rider`; V040 snapshots+platform_pricing; DeliveryFeePort bridge; public fee-estimate 30/min; formula (base+km×perKm)×surge |
| EPIC-011 | [STORY-007](./EPIC-011-rider-delivery/STORY-007-cod-reconciliation.md) | COD Reconciliation (Rider Side) | done | 2026-08-08 | `domains/rider`; V041 cod_collections/deposits; float ₹2000; finance mark-deposited (`finance:update`); rider COD view/request; no auto-confirm; FLOAT_RISK + 23:00 IST report outbox; OrderCodBridgeConfig |
| EPIC-011 | [STORY-008](./EPIC-011-rider-delivery/STORY-008-rider-incentives-performance.md) | Rider Incentives & Performance | done | 2026-08-08 | `domains/rider`; V042 earnings/payouts/badges + V043 payout Idempotency-Key; weekly Mon IST cron; Razorpay Route stub + 24h retry; HELD/COD deduct/₹100 carry-forward; Bruno synced; EPIC-015 incentives stub |

### EPIC-021 (`EPIC-021-settings-admin`)

| Epic | Story | Title | Status | Completed | Notes |
|------|-------|-------|--------|-----------|-------|
| EPIC-021 | [STORY-001](./EPIC-021-settings-admin/STORY-001-admin-staff-management.md) | Admin Staff Management | done | 2026-08-08 | `domains/settings`; V044 invite/reset cols; session revoke via SettingsAuthBridgeConfig; email logging stub (ids/tokenLen only); BR-9 INVITED re-send; audit append stub until STORY-003; password-set public endpoint deferred to auth |
| EPIC-021 | [STORY-002](./EPIC-021-settings-admin/STORY-002-feature-flags.md) | Feature Flag Management | done | 2026-08-08 | `domains/settings`; V045 feature_flags+seed; Redis 60s+invalidate; public check base enabled only; prod PATCH admin_super; seed-only (no POST/DELETE); cohort helper unit-tested |
| EPIC-021 | [STORY-003](./EPIC-021-settings-admin/STORY-003-platform-audit-log.md) | Platform Audit Log | done | 2026-08-08 | `domains/settings`; V046 enrich audit_log (not audit_logs); immutability+archived_at; middleware async; export always QUEUED; role filter deferred; pharmacy/catalogue writers adapted; export email logs urlLen only |
| EPIC-021 | [STORY-004](./EPIC-021-settings-admin/STORY-004-platform-configuration.md) | Platform Configuration | done | 2026-08-08 | `domains/settings`; V047 platform_config+config_history+seed (incl. orders.order_id_prefix immutable); Redis `platform_config` 60s; PlatformConfigReadPort exposed (customer wallet max-credit wiring deferred — no domain→domain deps); any-admin read all domains |
| EPIC-021 | [STORY-005](./EPIC-021-settings-admin/STORY-005-rbac-admin-roles.md) | Admin RBAC Role-Permission Matrix | done | 2026-08-08 | `domains/auth` matrix+`GET /roles/{role}/permissions`; API lists=STORY-005 exact; enforcement=`enforcementPermissionsFor`∪live extras (ops:`pharmacies:suspend`/`orders:dispatch`/`orders:*`/`riders:*`/`logistics:*`/`customers:read`/`finance:read`; finance:`finance:update`/`finance:*`/`pharmacies:read`; support:`pharmacies:read`; compliance:`pharmacies:update`/`customers:read`); `INSUFFICIENT_PERMISSIONS`+details; 405 via GlobalExceptionHandler; `domains/settings/PERMISSIONS.md` |

---

## Phase 2 — Finance + Pharmacy ERP


### EPIC-006 (`EPIC-006-pharmacy-inventory`)

| Epic | Story | Title | Status | Completed | Notes |
|------|-------|-------|--------|-----------|-------|
| EPIC-006 | [STORY-001](./EPIC-006-pharmacy-inventory/STORY-001-inventory-management.md) | Inventory Management - Stock Master, Product CRUD & Visibility Controls | done | 2026-08-09 | `domains/inventory`; V048 `pharmacy_product` (money paise); Growth gate via `medmate.inventory.growth-features-enabled`; sync EXCEL always (async >500 deferred); batches/movements empty until STORY-002; units_sold stub 0 |
| EPIC-006 | [STORY-002](./EPIC-006-pharmacy-inventory/STORY-002-batch-expiry-management.md) | Batch & Expiry Management - FEFO Tracking and Expiry Alerts | done | 2026-08-09 | `domains/inventory`; V049 `product_batch`+`batch_adjustment_log`+`inventory_stock_movement` (paise); duplicate batch → 201+`topped_up`; VaR=qty×purchase_price_paise; `FefoBatchSelectionPort` unit-tested (no POS deduct); sync Excel+minimal PDF expiry-report; staff write-off → `STAFF_CANNOT_WRITE_OFF` |
| EPIC-006 | [STORY-003](./EPIC-006-pharmacy-inventory/STORY-003-rack-location-management.md) | Rack Location Management - Physical Shelf Mapping | done | 2026-08-09 | `domains/inventory`; V050 `rack_location` (soft delete); product mapping via `pharmacy_product.rack_locations`; print-labels minimal PDF data URL (A4/QR/S3 deferred); POS rack search deferred EPIC-007 |
| EPIC-006 | [STORY-004](./EPIC-006-pharmacy-inventory/STORY-004-purchase-grn-management.md) | Purchase / GRN Management - Distributor Invoice Entry & Goods Received Notes | done | 2026-08-09 | `domains/inventory`; V051 distributors(minimal)+purchase_grn+purchase_grn_item (paise); DRAFT→STOCKED via save-and-stock (owner); free qty on batch, GST on paid qty; CSV import+confirm-import; no public distributor API (tests seed JDBC); `OrderInventoryBridgeConfig` pharmacy_product+is_online_visible with catalogue-mapping fallback |
| EPIC-006 | [STORY-005](./EPIC-006-pharmacy-inventory/STORY-005-distributor-management.md) | Distributor Management - Supplier Directory & Price Comparison | done | 2026-08-09 | `domains/inventory`; V052 expand distributors+`distributor_supply_item` (paise); Growth gate via `InventoryPlanPort`; GRN save-and-stock upserts supply items; outstanding_payable=Σ STOCKED GRN (repayments=0); Bruno `bruno/pharmacy/distributors/` |
| EPIC-006 | [STORY-006](./EPIC-006-pharmacy-inventory/STORY-006-reorder-suggestions.md) | Reorder Suggestions - Auto-Reorder Intelligence | done | 2026-08-09 | `domains/inventory`; V053 snapshot+PO+items (paise); `PharmacyReorderService` (avoids order `ReorderService` bean clash); Growth gate; nightly 02:00 IST; outbox `inventory.po.sent` ids-only; channel stub props; days_of_cover null until POS; Bruno `bruno/pharmacy/reorder/` |

### EPIC-007 (`EPIC-007-pharmacy-pos-billing`)

| Epic | Story | Title | Status | Completed | Notes |
|------|-------|-------|--------|-----------|-------|
| EPIC-007 | [STORY-001](./EPIC-007-pharmacy-pos-billing/STORY-001-counter-sale-pos.md) | Counter Sale POS - Real-Time Cart and Checkout | done | 2026-08-09 | `domains/pos`; V054 barcode+cart+invoice+items+settings+seq (paise); FEFO/stock via PosInventoryBridgeConfig; PDF CDN stub; Khata stub until STORY-003; PosTokenRestrictionFilter allows `/api/v1/pharmacy/pos/`; Bruno `bruno/pharmacy/pos/` |
| EPIC-007 | [STORY-002](./EPIC-007-pharmacy-pos-billing/STORY-002-gst-invoice-management.md) | GST Invoice Management - Generation, Customization & Sharing | done | 2026-08-09 | `domains/pos`; V054 reuse; list/detail/binary PDF/share/settings; PosPharmacy+PosNotification bridges; admin_finance/support GET read; minimal PDF not Puppeteer; Bruno invoices+invoice-settings |
| EPIC-007 | [STORY-003](./EPIC-007-pharmacy-pos-billing/STORY-003-credit-khata-management.md) | Credit / Khata Management - Customer Credit Tracking | done | 2026-08-09 | `domains/pos`; V055 khata tables+receipt seq (paise); Starter gate PosPlanPort; CREDIT checkout DEBIT+limit; tenancy via khata_customer_limit; remind 24h; Bruno `bruno/pharmacy/khata/` |
| EPIC-007 | [STORY-004](./EPIC-007-pharmacy-pos-billing/STORY-004-sales-ledger.md) | Sales Ledger - Complete Sales Audit Trail | done | 2026-08-09 | `domains/pos`; no new migration; period_summary+FY+export cap; mark-paid owner via service STAFF_CANNOT_MARK_PAID; CREDIT mark-paid → Khata (STORY-003); Bruno `bruno/pharmacy/sales/` |
| EPIC-007 | [STORY-005](./EPIC-007-pharmacy-pos-billing/STORY-005-pharmacy-offers-discounts.md) | Pharmacy Offers & Discounts - Promotions and Coupon Engine | done | 2026-08-09 | `domains/pos`; V056 pharmacy_offer+offer_redemption (paise); Growth gate; manual discount wins else highest counter offer; cart applied_offers; checkout redemption; admin offers OOS; Bruno `bruno/pharmacy/offers/` |

### EPIC-012 (`EPIC-012-payments-finance`)

| Epic | Story | Title | Status | Completed | Notes |
|------|-------|-------|--------|-----------|-------|
| EPIC-012 | [STORY-001](./EPIC-012-payments-finance/STORY-001-payment-processing.md) | Payment Processing (UPI / Card / COD) | done | 2026-08-09 | `domains/payment`; money BIGINT paise; Razorpay live/stub; thin financial_ledger writer; order PAYMENT_FAILED deferred (schema has no status) |
| EPIC-012 | [STORY-002](./EPIC-012-payments-finance/STORY-002-wallet-operations.md) | Namma Money Wallet Operations | done | 2026-08-09 | `domains/payment` façade + bridge to customer WalletService; no new wallet tables; `/wallet/debit|credit` permitAll like internal kyc; `GET .../wallet/balance` alias; `balance_before` derived from `balance_after`±amount (no V058); admin cap code `ADMIN_CREDIT_EXCEEDS_LIMIT`; story reasons ADMIN_CREDIT/CASHBACK→GOODWILL/PROMOTIONAL |
| EPIC-012 | [STORY-003](./EPIC-012-payments-finance/STORY-003-pharmacy-settlements.md) | Pharmacy Settlements | done | 2026-08-09 | `domains/payment` façades + V059 (reuse V019 `settlement`); API status `PENDING`↔storage `PENDING_RELEASE`; line items derived from orders (no `settlement_line_item`); RazorpayX live\|stub in payment, bridged to pharmacy port; ledger `PAYOUT_PHARMACY`+`TCS_COLLECTED`; Mon 06:00 cron reused; Bruno `admin/finance/settlements` + `pharmacy/finance/settlements`; legacy `/admin/pharmacies/{id}/settlements` unchanged |
| EPIC-012 | [STORY-004](./EPIC-012-payments-finance/STORY-004-rider-payouts.md) | Rider Payouts | done | 2026-08-09 | `domains/payment` façades + bridge on V042 `rider_payouts` (no V060); Mon cron+retry reused in rider; RazorpayX live\|stub bridged as rider Route; ledger `PAYOUT_RIDER` on finance release; API status `BELOW_THRESHOLD_CARRIED`↔storage `…_FORWARD`; no rider UPI/bank table — ACTIVE/ONLINE/OFFLINE/ON_TRIP treated Route-ready; legacy `/admin/riders/{id}/payout/release` unchanged (no ledger); Bruno `admin/finance/rider-payouts` + `rider/payouts/history` |
| EPIC-012 | [STORY-005](./EPIC-012-payments-finance/STORY-005-refund-processing.md) | Refund Processing | done | 2026-08-09 | `domains/payment` façade + `PaymentRefundBridgeConfig`; V061 PENDING/auto_processed/expected_by/processed_by/completed_at on shared `refund`; webhook `refund.processed`→complete; order threshold ≤₹500 auto else PENDING; COD/wallet→COMPLETED; ledger `REFUND`; Bruno `admin/finance/refunds` + `customers/me/refunds` |
| EPIC-012 | [STORY-006](./EPIC-012-payments-finance/STORY-006-cod-float-management.md) | COD Float Management (Finance Side) | done | 2026-08-09 | `domains/payment` façades + `PaymentCodFloatBridgeConfig`; V062 `cod_reconciliation_report`; reuse V041 COD tables; `/admin/finance/cod-float` (+ report/export/auto-reconcile) distinct from `/admin/finance/cod`; 23:00 IST via rider `FinanceCodDailyReconciliationPort` bridge; ledger `COD_DEPOSIT` on mark-deposited; Bruno `admin/finance/cod-float/` |
| EPIC-012 | [STORY-007](./EPIC-012-payments-finance/STORY-007-tax-gst-management.md) | Tax & GST Management | done | 2026-08-09 | `domains/payment`; V063 tax_filing+tcs_register (BIGINT paise); read-only panel; TCS register on settlement release; GSTR-8/TDS export local\|S3; admin_compliance via LIVE_EXTRA taxes:read/export; overdue via display overlay + daily job; other_input_gst=0 until STORY-008; Bruno `admin/finance/taxes/` |
| EPIC-012 | [STORY-008](./EPIC-012-payments-finance/STORY-008-financial-ledger.md) | Financial Ledger | done | 2026-08-09 | `domains/payment`; V064 append-only UPDATE/DELETE triggers + COD_DEPOSIT unique (STORY-006 FLAG); browse/export APIs + running_balance window; COMMISSION capture writer fixed to credit (AC-001); TCS_COLLECTED aliased as TCS in API; wallet credit/debit direction left as STORY-002; reuse TaxFilingObjectStore for CSV; Bruno `admin/finance/ledger/`; FinancialLedgerIT |
| EPIC-012 | [STORY-009](./EPIC-012-payments-finance/STORY-009-financial-overview-dashboard.md) | Financial Overview Dashboard | done | 2026-08-09 | `domains/payment`; read-only KPI/P&L/cash-position/ratios; Redis KPI TTL 60s; aggregates from payment+ledger+settlement+rider_payouts+refund+wallets+cod_collections (no V065); BR-006 ratios use ×100 (story −100 typo); AC-003 chart: hourly TODAY / daily 7D+; platform_net per AC-004 (excludes wallet); Bruno `admin/finance/overview/`; FinanceOverviewIT |

### EPIC-014 (`EPIC-014-crm-saas`)

| Epic | Story | Title | Status | Completed | Notes |
|------|-------|-------|--------|-----------|-------|
| EPIC-014 | [STORY-001](./EPIC-014-crm-saas/STORY-001-saas-plan-management.md) | SaaS Plan Management | done | 2026-08-09 | `domains/crm`; V065 plans/addons/matrix/crm_account; annual×10; paise DB→*_rs API; CrmPlanBridgeConfig Pos/Inventory; module filter opt-in; GROWTH↔RETAIL_PRO backfill |
| EPIC-014 | [STORY-002](./EPIC-014-crm-saas/STORY-002-subscription-management.md) | Subscription Management | done | 2026-08-09 | `domains/crm`; V066 saas_subscription; FREE bootstrap bridge; override≤90d; auto-renew T-3 IST; EXPIRED→FREE plan lookup; V073 Idempotency-Key on subscribe/upgrade + period-scoped auto-renew |
| EPIC-014 | [STORY-003](./EPIC-014-crm-saas/STORY-003-saas-billing-invoicing.md) | SaaS Billing and Invoicing | done | 2026-08-09 | `domains/crm`; V067 saas_invoice+lines; GST18% SAC9983; dunning Day3–14 outbox; Razorpay pay stub; Idempotency-Key; Bruno invoices+billing |
| EPIC-014 | [STORY-004](./EPIC-014-crm-saas/STORY-004-lead-pipeline.md) | Lead Pipeline | done | 2026-08-09 | `domains/crm`; V068 leads+activity+rr; weighted forecast; marketplace auto-lead; mark-won→subscribe; Bruno leads/ |
| EPIC-014 | [STORY-005](./EPIC-014-crm-saas/STORY-005-account-health-scoring.md) | Account Health Scoring | done | 2026-08-10 | `domains/crm`; V070 health+snapshot+save_play; bands+&lt;40 auto save-play; nightly 03:00 IST; support/GMV stubs; Bruno health/ |
| EPIC-014 | [STORY-006](./EPIC-014-crm-saas/STORY-006-feature-adoption-metering.md) | Feature Adoption Metering | done | 2026-08-10 | `domains/crm`; V069 module_usage+overrides; CrmUsageMeteringFilter DB upsert; nudge outbox; Bruno modules/ |
| EPIC-014 | [STORY-007](./EPIC-014-crm-saas/STORY-007-renewal-churn-management.md) | Renewal and Churn Management | done | 2026-08-10 | `domains/crm`; V071 churn_survey+cohorts; renewals pipeline; manual renew 7d; win-back/at-risk/monthly outbox; Bruno renewals/ |
| EPIC-014 | [STORY-008](./EPIC-014-crm-saas/STORY-008-saas-revenue-analytics.md) | SaaS Revenue Analytics | done | 2026-08-10 | `domains/crm`; V072 metrics_cache+cohort_retention+sm_spend; crm:analytics finance/super only; monthly batch+on-miss; Bruno analytics/ |

### EPIC-022 (`EPIC-022-external-integrations`)

| Epic | Story | Title | Status | Completed | Notes |
|------|-------|-------|--------|-----------|-------|
| EPIC-022 | [STORY-001](./EPIC-022-external-integrations/STORY-001-razorpay-integration.md) | Razorpay Integration | done | 2026-08-10 | `domains/integration`; V074; S2S X-Internal-Token; stub\|live; webhook HMAC+idempotency; payout retry 1h; payment-domain bridges deferred |
| EPIC-022 | [STORY-002](./EPIC-022-external-integrations/STORY-002-maps-geolocation.md) | Maps & Geolocation | done | 2026-08-10 | `domains/integration`; V075; Redis\|DB cache; zone ray-cast; GeocodePort+DistanceMatrixPort bridges |
| EPIC-022 | [STORY-003](./EPIC-022-external-integrations/STORY-003-government-api-integration.md) | Government API Integration | done | 2026-08-10 | `domains/integration`; V076; GSTN/DigiLocker/drug/FSSAI; pharmacy KYC bridges; rider Aadhaar deferred |
| EPIC-022 | [STORY-004](./EPIC-022-external-integrations/STORY-004-einvoicing-irn.md) | E-Invoicing IRN | done | 2026-08-10 | `domains/integration`; V077; GSP stub\|live; EinvoicePort bridge; POS finalize hook deferred |
| EPIC-022 | [STORY-005](./EPIC-022-external-integrations/STORY-005-accounting-integration.md) | Accounting Integration | done | 2026-08-10 | `domains/integration`; V078; Tally XML + Zoho Books stub; plan gate; auto-sync scheduler; Bruno `integrations/accounting/`; AC-001–008 covered |
| EPIC-022 | [STORY-006](./EPIC-022-external-integrations/STORY-006-communication-integrations.md) | Communication Integrations | done | 2026-08-10 | `domains/integration`; V079; admin control plane; health 5m; CommunicationChannelLookupPort for EPIC-017 |

---

## Phase 3 — Prescriptions + Growth


### EPIC-008 (`EPIC-008-prescription-management`)

| Epic | Story | Title | Status | Completed | Notes |
|------|-------|-------|--------|-----------|-------|
| EPIC-008 | [STORY-001](./EPIC-008-prescription-management/STORY-001-prescription-upload.md) | Prescription Upload and Storage | done | 2026-08-10 | `domains/prescription`; V080; multipart+local/S3 store; sync OCR stub; IST expiry; OrderPrescriptionPort bridge; Bruno prescriptions/ |
| EPIC-008 | [STORY-002](./EPIC-008-prescription-management/STORY-002-pharmacy-rx-queue.md) | Pharmacy Prescription Review and Dispense Workflow | done | 2026-08-10 | `domains/prescription`; V081; queue approve/reject/dispense; CRM plan gate; POS/register ports stub; Bruno pharmacy/prescriptions/ |
| EPIC-008 | [STORY-003](./EPIC-008-prescription-management/STORY-003-rx-compliance-audit.md) | Admin Rx Compliance Audit | done | 2026-08-10 | `domains/prescription`; V082; rx_audit_entry+append-only activity_log; 15m overdue; ops no file_url; Bruno admin/prescriptions/ |
| EPIC-008 | [STORY-004](./EPIC-008-prescription-management/STORY-004-schedule-drug-register.md) | Statutory Schedule H1/X Drug Register | done | 2026-08-10 | `domains/prescription`; V083; sync register on H1/X dispense; export jobs; archival; Bruno admin+pharmacy drug-register/ |
| EPIC-008 | [STORY-005](./EPIC-008-prescription-management/STORY-005-doctor-registry.md) | Prescribing Doctor Registry and Verification | done | 2026-08-10 | `domains/prescription`; V084; doctor registry verify/blacklist; OCR upsert; DoctorCardPort; Bruno admin/doctors/ |
| EPIC-008 | [STORY-006](./EPIC-008-prescription-management/STORY-006-compliance-reports-filings.md) | Regulatory Compliance Filings and Reports | done | 2026-08-10 | `domains/prescription`; V085; filings crons IST; generate/mark-filed; drug-recall zeros batch qty+refreshes denorm; activity-log; Bruno admin/compliance/ |

### EPIC-009 (`EPIC-009-doctor-teleconsult`)

| Epic | Story | Title | Status | Completed | Notes |
|------|-------|-------|--------|-----------|-------|
| EPIC-009 | [STORY-001](./EPIC-009-doctor-teleconsult/STORY-001-doctor-profile-management.md) | Teleconsult Doctor Profile Management | done | 2026-08-10 | `domains/teleconsult`; V086 `teleconsult_doctors`; Bruno `bruno/admin/teleconsult/doctors/`; AES-GCM `teleconsultPhoneCipher` for `internal_phone` (never in API); midnight IST `consults_today` reset gated by `medmate.teleconsult.jobs.enabled`; stats period aggregates zero until STORY-003 consult table; LRU `selectLeastRecentlyAssigned` + running-avg helpers unit-tested for AC-005/006; UUID v4 (no `tdoc_` prefixes); BUMS in qualification allow-list |
| EPIC-009 | [STORY-002](./EPIC-009-doctor-teleconsult/STORY-002-consult-request-scheduling.md) | Patient Consultation Request and Scheduling | done | 2026-08-10 | `domains/teleconsult`; V087 `consults`; Bruno `bruno/consults/`; CartPort + NotificationDispatchPort stub/outbox bridge; NOW LRU assign + queue wait=`position×rolling_7d_avg` (default 7); auto-cancel +30m gated by `medmate.teleconsult.jobs.enabled`; UUID v4; e-Rx cart link AC-005 deferred to STORY-004 |
| EPIC-009 | [STORY-003](./EPIC-009-doctor-teleconsult/STORY-003-consult-session-management.md) | Teleconsult Session Lifecycle Management | done | 2026-08-10 | `domains/teleconsult`; V088 `consult_status_events` + duration/rated_at/clinical_notes; Bruno `bruno/admin/consults/` + `bruno/consults/rate.bru`; doctor stats period aggregates from COMPLETED consults |
| EPIC-009 | [STORY-004](./EPIC-009-doctor-teleconsult/STORY-004-eprescription-generation.md) | e-Prescription Generation and Linking | done | 2026-08-10 | domains/teleconsult + prescription bridge; V089; Bruno admin/consults/eprescription + prescriptions/eprescriptions |

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
