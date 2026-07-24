# EPIC-019: Automation and Rules Engine

| Field | Value |
|-------|-------|
| Epic ID | EPIC-019 |
| Epic Name | Automation and Rules Engine |
| Product | Namma MedMate |
| Domain | Automation, Workflow, Operations |
| Priority | P1 - Critical |
| Status | In Development |
| Owner | Platform Squad |
| Last Updated | 2026-07-24 |

## Overview

EPIC-019 delivers the Namma MedMate Automation Engine - an event-driven rules system that executes platform-wide automated actions in response to business triggers. The engine follows a WHEN (trigger) ? IF (conditions) ? THEN (actions) model. It supports simple single-step rules, multi-step sequential workflows with wait and branch logic, a simulation/dry-run mode for safe testing, a human-in-the-loop approvals queue for high-value actions, a comprehensive activity audit log, and a global kill switch for emergency suspension of all automation. Six production-ready seed rules are shipped with the platform.

## Goals

- Automate repetitive operational decisions (rider assignment, payout release, dunning, escalation)
- Enable non-engineers to configure, simulate, and deploy automation rules via admin UI
- Guarantee safety with guardrails: rate limits, value caps, simulation mode, and kill switch
- Provide full audit trail for every automated action for compliance and debugging
- Ship 6 seed automations on day one covering the most critical operational use cases

## Stories

| Story ID | Title | Description |
|----------|-------|-------------|
| STORY-001 | Rules Engine Core | Trigger registry, condition evaluator, action executor |
| STORY-002 | Rule CRUD Management | Create, read, update, delete, and toggle automation rules |
| STORY-003 | Workflow / Journey Builder | Multi-step workflow sequences with waits and branches |
| STORY-004 | Rule Simulation | Simulation / dry-run mode for rules before enabling |
| STORY-005 | Activity Log & Audit | Automation activity feed and rollback |
| STORY-006 | Approvals Queue | Human-in-the-loop approvals for high-value actions |
| STORY-007 | Automation Health & Kill Switch | Health dashboard and global kill switch |
| STORY-008 | Seed Automations | Pre-built seed automation rules |

## Automation Architecture

```
Event Bus ? Trigger Registry ? Rule Evaluator ? Condition Check ? Action Executor ? Activity Log
                                                                         ?
                                                               Approvals Queue (if above cap)
```

## Key Design Principles

1. **Idempotency**: Same event + same rule within dedup_window fires at most once
2. **Non-blocking**: Rule evaluation is async (< 500ms SLA); actions run asynchronously
3. **Safe by default**: All new rules start INACTIVE; simulation mode available
4. **Immutable audit log**: All automation actions are permanently logged
5. **Kill switch safety**: Global PAUSE suspends all evaluation instantly

## Dependencies

| Dependency | Type |
|-----------|------|
| EPIC-001 Order Management | Trigger source |
| EPIC-004 Dispatch | Trigger source + action target |
| EPIC-005 Finance | Trigger source + action target |
| EPIC-006 Pharmacy | Trigger source + action target |
| EPIC-007 Rider | Trigger source + action target |
| EPIC-012 CRM | Trigger source + action target |
| EPIC-013 Support | Trigger source + action target |
| EPIC-017 Notifications | Action channel |
| Message queue (SQS/Redis) | Infrastructure |
