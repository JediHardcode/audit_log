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

EARS form: `WHEN <trigger> THEN <observable outcome>`.

### Filtering

- **AC-F1** WHEN client sends `GET /audit-events?actor=u_42` THEN response 200 contains only items where `actor == "u_42"`.
- **AC-F2** WHEN client sends `GET /audit-events?resource=order/` THEN response 200 contains only items where `resource` starts with literal `order/`.
- **AC-F3** WHEN client sends `GET /audit-events?from=T1&to=T2` THEN response 200 contains only items where `T1 <= timestamp < T2` (lower inclusive, upper exclusive).
- **AC-F4** WHEN event row has `timestamp == T1` AND request uses `from=T1` THEN that row is included.
- **AC-F5** WHEN event row has `timestamp == T2` AND request uses `to=T2` THEN that row is excluded.
- **AC-F6** WHEN client sends `GET /audit-events?outcome=success` THEN response 200 contains only items where `outcome == "success"`.
- **AC-F7** WHEN client sends request without filters THEN response 200 returns latest events sorted by `timestamp` desc.

### Sorting and pagination

- **AC-P1** WHEN client sends `sort=desc` THEN items are ordered by `(timestamp DESC, id DESC)`.
- **AC-P2** WHEN client sends `sort=asc` THEN items are ordered by `(timestamp ASC, id ASC)`.
- **AC-P3** WHEN result has more rows than `limit` THEN response includes non-null `nextCursor`.
- **AC-P4** WHEN result fits in single page THEN response has `nextCursor == null`.
- **AC-P5** WHEN result is empty THEN response is `{ "items": [], "nextCursor": null }`.
- **AC-P6** WHEN client fetches page 1, new events with later timestamps are inserted, then client fetches page 2 via cursor THEN page 2 contains only originally-seeded events older than page 1's last row; no duplicates, no skips.
- **AC-P7** WHEN client sends `limit=500` THEN response 200 returns at most 200 items (silent clamp, not 400).
- **AC-P8** WHEN client sends `limit` absent THEN default is 50.

### Validation errors (HTTP 400)

- **AC-E1** WHEN `from` or `to` is not valid ISO-8601 THEN response is 400.
- **AC-E2** WHEN `outcome` is not in `{success, denied, error}` (case-sensitive) THEN response is 400.
- **AC-E3** WHEN `sort` is not in `{asc, desc}` THEN response is 400.
- **AC-E4** WHEN `limit` is non-numeric or `< 1` THEN response is 400.
- **AC-E5** WHEN `from > to` THEN response is 400.
- **AC-E6** WHEN both `from` and `to` set AND `to - from > 90 days` THEN response is 400.
- **AC-E7** WHEN only `from` set OR only `to` set THEN 90-day window check is skipped (no 400).
- **AC-E8** WHEN `cursor` is unparseable (bad base64 or bad JSON) THEN response is 400.
- **AC-E9** WHEN `cursor` filter hash differs from current request filter hash THEN response is 400.
- **AC-E10** WHEN `cursor` `sort` differs from current request `sort` THEN response is 400.

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
