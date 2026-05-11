# Query API Implementation Plan

One task = one safe commit. Implement tasks in order.

## 1. Add query API response and request types

Add the read-side API model before adding behavior.

Success criteria:

- `AuditEventQuery` represents all supported query params: `actor`, `resource`, `from`, `to`, `outcome`, `sort`, `limit`, `cursor`.
- `AuditEventDto` mirrors the event model using flat fields and the existing names `timestamp` and `context`.
- `AuditEventPage` returns `items` and `nextCursor`.
- No controller, repository, or SQL behavior is added in this commit.

## 2. Add read repository for audit event queries

Add `AuditEventReadRepository` using `NamedParameterJdbcTemplate`.

Success criteria:

- Query supports optional filters for exact `actor`, resource prefix, `[from, to)` timestamp range, and exact `outcome`.
- Query orders by `timestamp`, then `id`.
- Both `asc` and `desc` directions are supported.
- No JPA entities are introduced.

## 3. Add cursor encoding and validation

Add cursor encode/decode behavior used by pagination.

Success criteria:

- Cursor is URL-safe base64 without padding.
- Cursor JSON contains `ts`, `id`, `s`, and `f`.
- Filter hash uses SHA-256 over the effective filters: `actor`, `resource`, `from`, `to`, and `outcome`.
- Invalid cursor, filter mismatch, and sort mismatch map to HTTP 400.

## 4. Add query validation

Add validation for request params and semantic limits.

Success criteria:

- Invalid `from`, `to`, `outcome`, `sort`, `limit`, and cursor parse errors map to HTTP 400.
- `from > to` maps to HTTP 400.
- `to - from > 90 days` maps to HTTP 400.
- Open-ended ranges are allowed.
- `limit > 200` is silently clamped to 200.
- Non-numeric `limit` and `limit < 1` map to HTTP 400.

## 5. Add query service

Add `AuditEventQueryService` to coordinate validation, cursor handling, repository calls, and response building.

Success criteria:

- Timestamps are normalized to microsecond precision before comparison and cursor generation.
- Repository fetches `limit + 1` rows.
- Service returns only the requested page size.
- `nextCursor` is generated from the last returned item when another page exists.
- `nextCursor` is `null` for empty results and the last page.

## 6. Add GET /audit-events controller

Expose the read endpoint.

Success criteria:

- `GET /audit-events` binds query params into `AuditEventQuery`.
- Response shape exactly matches the design: flat `items` plus `nextCursor`.
- Endpoint uses the same Basic-auth role policy as the existing audit endpoints.
- Existing `POST /audit-events` behavior remains unchanged.

## 7. Verify and add required indexes

Confirm query paths have required Postgres indexes.

Success criteria:

- Required indexes exist for `(timestamp DESC, id DESC)`, actor queries, resource prefix queries, and outcome queries.
- Add a Flyway migration only for missing indexes.
- No write-side storage behavior changes are introduced.
- Integration test for indexes (by information_schema)

## 8. Add integration tests for filters and sorting

Add Testcontainers coverage for primary query behavior.

Success criteria:

- Actor exact filter is covered.
- Resource prefix filter is covered.
- `[from, to)` boundary inclusivity is covered.
- Outcome filter is covered.
- `sort=asc` and `sort=desc` are covered.

## 9. Add integration tests for pagination stability

Cover cursor pagination under concurrent inserts.

Success criteria:

- Seed multiple events and fetch page 1.
- Insert newer events after page 1.
- Fetch page 2 using the cursor.
- Assert page 2 contains only the expected older seeded events.
- Assert no duplicate or skipped original rows.
- Cover empty result and last page `nextCursor = null`.

## 10. Add integration tests for validation errors

Cover all required HTTP 400 cases and limit clamping.

Success criteria:

- Invalid timestamps, cursor, outcome, sort, and limit are covered.
- `from > to` and `to - from > 90 days` are covered.
- Cursor filter mismatch and cursor sort mismatch are covered.
- `limit=500` returns success and clamps to max 200.

## 11. Add resource prefix escaping tests

Cover SQL `LIKE` escaping behavior.

Success criteria:

- `%`, `_`, and `\` in client input are escaped.
- Wildcard-looking resource input does not broaden matches.
- Literal prefix matches still work.

## 12. Update API documentation

Update project API docs after implementation.

Success criteria:

- `CLAUDE.md` API section includes `GET /audit-events`.
- Documentation references `.specs/query-api/`.
- Documentation does not include out-of-scope observability or write-side changes.

## Verification

Run after implementation:

```bash
./gradlew spotless
```

```bash
./gradlew test
```
 
## Assumptions

- Task text is English.
- One task maps to one safe commit.
- `GET /audit-events` reuses the existing Basic-auth role policy from current audit endpoints.
- No changes are made to `POST /audit-events`, the event model, field names, or write-side behavior.
- Storage changes are limited to missing query indexes, if any.
