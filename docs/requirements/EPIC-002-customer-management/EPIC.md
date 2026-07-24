# EPIC-002: Customer Management

| Attribute | Value |
|-----------|-------|
| **Epic ID** | EPIC-002 |
| **Domain** | Customer Management |
| **Priority** | P0 |
| **Status** | Draft |

## Overview

EPIC-002 covers the complete lifecycle of a customer on the Namma MedMate platform: profile management, delivery address book, the Namma Money wallet, saved payment methods, and the loyalty + referral programme. These capabilities underpin every customer-facing transaction and provide the Admin HQ with the tooling to segment, flag, notify, and analyse customers at scale. The stories in this epic are foundational to checkout (wallet balance, saved addresses), retention (loyalty tiers, referrals), and trust & safety (customer flagging, dispute tracking).

## Goals

1. Enable customers to manage a complete, reusable profile (name, avatar, DOB, language preference) with minimal friction.
2. Provide a flexible, multi-address book with lat/lng for precise delivery zone matching.
3. Operate a closed-loop Namma Money wallet for refunds, goodwill credits, and promotional disbursements.
4. Tokenise and securely store up to 5 UPI IDs and 5 cards per customer via Razorpay, with zero raw card data on-platform.
5. Drive retention through a tiered loyalty programme and a referral system that rewards both parties after a qualifying delivery.

## Scope

### In Scope
- Customer profile CRUD (self-service and admin views)
- Customer segmentation (NEW / REGULAR / LOYAL / VIP) and flagging by admin
- Saved delivery addresses with geocoding support (max 10 per customer)
- Namma Money wallet - balance, transactions, admin credit
- Saved payment methods - UPI ID validation + card tokenisation via Razorpay
- Loyalty tier tracking and point history
- Referral code generation, application, and reward disbursement

### Out of Scope
- Order placement and checkout - EPIC-003
- Prescription upload and management - EPIC-005
- Push notification content and templates - EPIC-015
- Customer support tickets - EPIC-012

## Stories

| Story ID | Title | Priority | Complexity |
|----------|-------|----------|------------|
| STORY-001 | Customer Profile Management | P0 | M |
| STORY-002 | Delivery Address Management | P0 | M |
| STORY-003 | Namma Money Wallet | P0 | L |
| STORY-004 | Saved Payment Methods | P1 | M |
| STORY-005 | Loyalty Points & Referral Programme | P1 | L |

## Success Metrics

- Average addresses per customer ? 1.5 within 30 days of first order
- Wallet utilisation rate ? 30% of eligible orders (where wallet balance > 0)
- Referral conversion rate ? 15% (referred customers completing first order)
- Loyalty SILVER+ customer 90-day retention ? 70%
- Saved payment method usage rate ? 50% of transactions

## Dependencies

- EPIC-001 - Authentication & Identity (customer must be authenticated for all profile APIs)
- EPIC-003 - Order Management (wallet debit, loyalty point award happen at order completion)
- EPIC-008 - Payment Gateway (Razorpay UPI VPA validation and card tokenisation)
