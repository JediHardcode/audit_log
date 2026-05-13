
## Endpoint

`GET /audit-events`

### Query parameters

| Param    | Type                              | Required | Semantics                                          |
|----------|-----------------------------------|----------|----------------------------------------------------|
| actor    | string (comma-separated, ≤10)     | no       | Exact match; multi-value = OR. See `### Multi-actor filter` |
| resource | string                            | no       | Prefix match (e.g. `order/` returns all `order/*`) |
| from     | ISO-8601 instant (UTC)            | no       | Inclusive lower bound on `timestamp`               |
| to       | ISO-8601 instant (UTC)            | no       | Exclusive upper bound on `timestamp`               |
| outcome  | enum (`success`/`denied`/`error`) | no       | Exact match, **strict lowercase**                  |
| sort     | `asc` / `desc`                    | no       | Sort by `timestamp` then `id`. Default `desc`      |
| limit    | int                               | no       | Default 50, max 200 (silently clamped). Min 1      |
| cursor   | opaque base64                     | no       | Continuation token from previous response          |

All filters are optional. With no filters, returns latest events by `timestamp`
desc.

### Identifiers

- `id` type: **UUID** (existing column type). UUIDs sort lexicographically and
  provide a stable tiebreaker for events sharing the same `timestamp`. ULID
  migration is out of scope — write path remains untouched.

### Response

```json
{
  "items": [
    {
      "id": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
      "timestamp": "2026-04-17T11:02:14Z",
      "actor": "u_42",
      "action": "order.refunded",
      "resource": "order/9f3b...",
      "outcome": "success",
      "context": { "...": "..." }
    }
  ],
  "nextCursor": "eyJ0cyI6Li4uLCJpZCI6Li4ufQ=="
}
```

#### Response edge cases

- Empty result: `{ "items": [], "nextCursor": null }`
- Last page: `nextCursor: null`
- No `total` / `count` field — out of scope (avoid full-table scan).

### Pagination

- Cursor is opaque base64 (URL-safe, no padding) of JSON `{"ts":"<ISO>","id":"<UUID>","s":"asc|desc","f":"<sha256-hex of filters>"}`.
  Clients must not parse it.
- Stable sort: `timestamp` then `id` as tiebreaker.
- Direction follows `sort` param.

#### Cursor SQL semantics (keyset pagination)

- `desc`: `WHERE (timestamp, id) < (cursor.ts, cursor.id)`
- `asc`:  `WHERE (timestamp, id) > (cursor.ts, cursor.id)`
- Row tuple comparison — single index seek, stable under concurrent inserts.

#### Cursor invalidation

- Cursor encodes `sort` + hash of filters (`actor`, `resource`, `from`, `to`, `outcome`).
- If incoming request has different `sort` or filter hash → **400 invalid cursor**.
- No HMAC signing (internal service, trusted callers); tampering yields 400 on parse or hash mismatch.

##### Filter hash canonical form

SHA-256 hex over the following byte sequence, fields in fixed order, `\n` (LF, 0x0A) separator. Missing values serialized as empty string. Single newline between fields, no trailing newline:

```
actor      := normalized actor set, elements joined by ',' (comma). See `### Multi-actor filter` for normalization.
resource   := raw user input (pre-LIKE-escape, post-trim of leading/trailing whitespace)
from       := ISO-8601 instant truncated to microseconds, or ""
to         := ISO-8601 instant truncated to microseconds, or ""
outcome    := lowercase enum literal, or ""

input      := actor + "\n" + resource + "\n" + from + "\n" + to + "\n" + outcome
hash       := lower-hex(SHA-256(UTF-8(input)))
```

Two requests producing identical normalized inputs MUST yield byte-identical hashes. Examples:

- `actor=u_42,svc_billing` and `actor=svc_billing,u_42` → same hash (sort stage).
- `actor=u_42,,u_42` and `actor=u_42` → same hash (blank-strip + dedup).
- `actor=U_42` and `actor=u_42` → **different** hash (case-sensitive).

#### Timestamp precision

- Postgres `timestamptz` stores microseconds; Java `Instant` carries nanoseconds.
- All timestamps truncated to **microseconds** before write, comparison, and cursor encoding to avoid boundary mismatches.

### Multi-actor filter

- `actor` query param accepts a comma-separated list (`actor=u_42,svc_billing`).
- Single value remains backward-compatible — same wire format, same SQL path.
- Normalization pipeline (applied identically by validator, repository, and cursor hash):
  1. Split on `,`.
  2. Trim each value; drop blanks.
  3. Deduplicate (set semantics).
  4. Sort ascending by Java `String.compareTo` (UTF-16 code unit order, case-sensitive).
- Cap: > 10 distinct non-blank values → **400** (parse error).
- Empty effective set (e.g. `actor=,,`) → no actor filter applied.
- SQL: `actor = ANY(:actors)` bound as `text[]`. With one element, planner falls back to equality — same plan as the scalar case.
- Index path: existing `(actor, timestamp DESC, id DESC)` composite serves each element via BitmapOr; no new index required for the 10-element cap.

### Resource prefix matching

- SQL: `resource LIKE :prefix || '%' ESCAPE '\'`
- User input escaped: `\` → `\\`, `%` → `\%`, `_` → `\_` before binding.
- Prevents wildcard injection from client.

### Validation / Errors (all 400)

Parse errors:
- Invalid ISO-8601 in `from` / `to`
- Invalid or unparseable `cursor`
- `outcome` not in {`success`, `denied`, `error`}
- `sort` not in {`asc`, `desc`}
- `limit` non-numeric or `< 1`

Semantic errors:
- `from > to`
- `to - from > 90 days`
- Cursor `sort` or filter hash mismatch with current request

Behavior:
- `limit > 200` → silently clamped to 200 (not a 400)
- Only `from` set, no `to` → allowed; 90d window check skipped
- Only `to` set, no `from` → allowed; 90d window check skipped
- Neither `from` nor `to` → allowed; query may scan large range — protected by `limit` + composite index

### Indexes

Required Postgres indexes (verify presence; add Flyway migration if missing — schema changes don’t violate append-only data invariant):

- `(timestamp DESC, id DESC)` — composite, drives keyset pagination
- `(actor, timestamp DESC, id DESC)` — for `actor=` filter + sort
- `(resource text_pattern_ops, timestamp DESC, id DESC)` — for `LIKE 'prefix%'` + sort
- `(outcome, timestamp DESC, id DESC)` — for `outcome=` filter + sort

Plain `timestamp` index alone insufficient for p95 < 300ms under filtered keyset.

### Layering / classes

- `AuditEventQueryController` — `@GetMapping("/audit-events")`, binds query params to `AuditEventQuery`.
- `AuditEventQuery` — request POJO **with builder** (Lombok `@Builder` or hand-written). Fields: `actor`, `resource`, `from`, `to`, `outcome`, `sort`, `limit`, `cursor`.
- `AuditEventQueryService` — validates, decodes/encodes cursor, calls repository, builds page.
- `AuditEventReadRepository` — `NamedParameterJdbcTemplate`. **No JPA, no entities** (CLAUDE.md rule).
- `AuditEventDto` — single item shape (mirrors event model, flat).
- `AuditEventPage` — response: `List<AuditEventDto> items`, `String nextCursor`.

### Auth

- Role auth (spring security), Basic auth

### Test plan (Testcontainers + real Postgres)

Per acceptance criteria in requirements.md, plus:

- **Stable order under inserts**: inject controllable `Clock` bean; seed N events, fetch page 1, insert M new events with later timestamps, fetch page 2 via cursor — assert page 2 contains only originally-seeded events older than page 1’s last row.
- **Cursor invalidation**: fetch page with `actor=A`, then reuse cursor with `actor=B` → expect 400.
- **Direction switch**: fetch with `sort=desc`, reuse cursor with `sort=asc` → expect 400.
- **Resource prefix escape**: insert resource literally `order/100%off`; query `resource=order/100\%` should match; query `resource=order/100` should also match (prefix); query `resource=order/_` should NOT match unrelated rows via wildcard.
- **Limit clamp**: pass `limit=500` → response has ≤200 items, no error.
- **Boundary**: event at exactly `from` included; event at exactly `to` excluded.

### Documentation update

After implementation, update `CLAUDE.md` `## API` section:

```
GET  /audit-events            — поиск по actor / resource / time range / outcome,
                                cursor pagination. Spec: .specs/query-api/
```
