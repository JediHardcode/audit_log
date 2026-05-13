## Context

Internal compliance/SRE/security readers need a way to retrieve audit events
already stored by the service (POST /audit-events). This task adds a read-only
`GET /audit-events` endpoint with filtering and cursor pagination. No write-side
or schema changes.

## Goal

A single HTTP endpoint that lets internal callers search audit events by
`actor`, `resource`, time range, and `outcome`, returning results in stable
order with cursor-based pagination.

`nextCursor = null` means no more pages. Field names match the event model in
`CLAUDE.md` (`timestamp`, `context`) — not renamed.


### Non-functional

- p95 latency < 300ms on indexed query (actor / resource / timestamp indexes
  already exist).



### Resource prefix escaping

- **AC-X1** WHEN client sends `resource=order/100\%` AND row has resource literally `order/100%off` THEN that row matches.
- **AC-X2** WHEN client sends `resource=order/_` THEN unrelated rows whose resource does NOT start with literal `order/_` do NOT match (no SQL wildcard injection).
- **AC-X3** WHEN client input contains `\`, `%`, or `_` THEN those characters are escaped before SQL `LIKE` binding.

### Response shape

- **AC-R1** WHEN response 200 returns items THEN each item has flat fields `id`, `timestamp`, `actor`, `action`, `resource`, `outcome`, `context` — matches example in `design.md#response`.
- **AC-R2** WHEN response 200 returns THEN top-level shape is exactly `{ "items": [...], "nextCursor": <string|null> }` — no `total`, no `count`.

### Test coverage requirement

All ACs above must be covered by integration tests using Testcontainers with real Postgres. No DB mocks.

## Out of scope

- Observability: metrics, access logs (separate ticket)
- Any change to POST /audit-events, event model, or storage schema
- Field renames (`occurredAt`, `payload`) — keep event-model names
- Nested `actor` / `resource` objects with `type` — keep flat strings

## Open questions

(none — resolved in design Q&A on 2026-05-06)
