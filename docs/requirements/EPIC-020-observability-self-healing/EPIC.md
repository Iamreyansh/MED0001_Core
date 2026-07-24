# EPIC-020: Observability and Self-Healing

| Field        | Value                                         |
| ------------ | --------------------------------------------- |
| Epic ID      | EPIC-020                                      |
| Epic Name    | Observability and Self-Healing                |
| Product      | Namma MedMate                                 |
| Domain       | Platform Operations, SRE, Incident Management |
| Priority     | P1 - Critical                                 |
| Status       | In Development                                |
| Owner        | Platform SRE Squad                            |
| Last Updated | 2026-07-24                                    |

## Overview

EPIC-020 delivers real-time platform monitoring, automated anomaly alerting, self-healing playbooks, SLO tracking, and incident lifecycle management for Namma MedMate. It gives the operations team a live command center view of platform health, auto-remediates common issues (dark zones, failed payment jobs, low fill-rate pharmacies), and maintains a structured incident process for P1/P2 outages. Error budget tracking against defined SLOs ensures operational excellence commitments are visible and tracked.

## Goals

- Provide real-time visibility into platform health with < 60-second metric latency
- Auto-remediate known failure modes without human intervention
- Track SLOs and error budget to enforce operational excellence standards
- Provide structured incident lifecycle for P1/P2 outages with postmortem workflow
- Page on-call engineers and admin team automatically on critical alerts

## Stories

| Story ID  | Title                           | Description                                          |
| --------- | ------------------------------- | ---------------------------------------------------- |
| STORY-001 | Real-Time Monitoring & Alerting | Live platform health monitoring and anomaly alerting |
| STORY-002 | Auto-Remediation                | Automated self-healing playbooks                     |
| STORY-003 | SLO & Incident Management       | SLO tracking and incident lifecycle                  |

## Platform SLOs

| SLO Name              | Target  | Measurement                                      |
| --------------------- | ------- | ------------------------------------------------ |
| Order SLA Adherence   | 95%     | Orders delivered within 45 min / total delivered |
| Payment Success Rate  | 99%     | Successful payment captures / total attempts     |
| Dispatch Success Rate | 98%     | Orders assigned within 10 min / total orders     |
| API P99 Latency       | < 500ms | P99 response time across all API endpoints       |

## Dependencies

| Dependency                           | Type                 |
| ------------------------------------ | -------------------- |
| EPIC-001 Order Management            | Metrics source       |
| EPIC-004 Dispatch                    | Metrics source       |
| EPIC-005 Finance                     | Metrics source       |
| EPIC-019 Automation Engine           | Remediation executor |
| EPIC-017 Notifications               | Alert delivery       |
| Metrics store (TimescaleDB/InfluxDB) | Infrastructure       |
