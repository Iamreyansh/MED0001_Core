# EPIC-016: Analytics and Reporting

| Field | Value |
|-------|-------|
| Epic ID | EPIC-016 |
| Epic Name | Analytics and Reporting |
| Product | Namma MedMate |
| Domain | Business Intelligence, Reporting, Data |
| Priority | P1 - Critical |
| Status | In Development |
| Owner | Product - Admin HQ Squad |
| Last Updated | 2026-07-24 |

## Overview

EPIC-016 covers all analytics, reporting, and data-insight capabilities across the Namma MedMate platform. It delivers a comprehensive Admin HQ analytics suite for operations and finance visibility, a Pharmacy Dashboard analytics module for pharmacy owners, geography and supply-demand analysis for logistics planning, and a scheduled report library for compliance and stakeholder reporting. Together these stories give every stakeholder the data they need to make decisions, track performance, and meet regulatory obligations.

## Goals

- Provide real-time and periodic GMV, revenue, and operations KPIs to admin
- Enable pharmacy owners to self-serve sales, GST, and P&L analytics within their dashboard
- Surface cohort retention and acquisition analytics for growth decision-making
- Deliver zone-level geography analytics to power logistics rebalancing
- Automate report generation and scheduling for compliance, finance, and operations

## Stories

| Story ID | Title | Description |
|----------|-------|-------------|
| STORY-001 | Platform Overview Analytics | GMV, orders, revenue KPI dashboard for admin |
| STORY-002 | Operations & SLA Analytics | Fulfilment metrics, SLA adherence, delivery performance |
| STORY-003 | Growth & Cohort Analytics | Customer acquisition, retention, and cohort analysis |
| STORY-004 | Pharmacy Analytics | Pharmacy-level sales, GST, and P&L analytics |
| STORY-005 | Geography Analytics | Zone-level GMV, supply-demand, and coverage analytics |
| STORY-006 | Report Library | Scheduled and on-demand report generation for admin |

## Cross-Cutting Concerns

- All analytics are scoped by authenticated role (admin vs pharmacy)
- Period selectors follow ISO 8601 date ranges; `TODAY` uses live data, 90D+ uses pre-aggregated tables
- Analytics endpoints require Growth plan or above for pharmacy-side access (403 for Free/Starter)
- Large report generation is always async (> 10,000 rows)
- Pre-aggregated data tables updated nightly via batch jobs
- All analytics endpoints are read-only (GET); no mutations
- Indian Fiscal Year: April 1 - March 31

## Dependencies

| Dependency | Type |
|-----------|------|
| EPIC-001 Order Management | Data source for all metrics |
| EPIC-005 Finance & Payments | Revenue, payout, and commission data |
| EPIC-006 Pharmacy Onboarding | Pharmacy dimension data |
| EPIC-007 Rider Management | Rider performance data |
| EPIC-012 CRM & Subscriptions | Health scores, plan tiers |
| AWS S3 | Report file storage |
| Pre-aggregation batch jobs | 90D+ analytics performance |
