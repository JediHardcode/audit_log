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

## Acceptance criteria

- Endpoint exists and accepts the query params above with the stated semantics.
- Integration test (Testcontainers, real Postgres) covers:
  - Filter by `actor` exact
  - Filter by `resource` prefix
  - Filter by `[from, to)` range — boundary inclusivity
  - Filter by `outcome`
  - Cursor pagination across multiple pages, stable order under inserts
  - `sort=asc` and `sort=desc`
  - Each 400 error case listed above
  - `limit` clamping to 200
- Response shape matches the example exactly (flat fields, `nextCursor`).

## Out of scope

- Observability: metrics, access logs (separate ticket)
- Any change to POST /audit-events, event model, or storage schema
- Field renames (`occurredAt`, `payload`) — keep event-model names
- Nested `actor` / `resource` objects with `type` — keep flat strings

## Open questions

(none — resolved in design Q&A on 2026-05-06)
