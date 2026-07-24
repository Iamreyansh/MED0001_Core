# ADR-003: Realtime — polling now, WebSocket later

## Decision

Order/admin “live” feeds use HTTP polling (requirements). Rider GPS push (EPIC-011 STORY-004) is deferred to API Gateway WebSocket or a dedicated realtime service — not part of bootstrap HTTP Lambda.

## Consequences

- Keep polling rate limits sane (e.g. 60/min).
- Do not block core API design on sticky WS connections.
