# Query API Tasks

One task = one safe commit.

Source documents:

- [requirements.md](./requirements.md)
- [design.md](./design.md)

## Tasks

- [ ] 1. Add query API response and request types
  - Add `AuditEventQuery`, `AuditEventDto`, and `AuditEventPage`.
  - Keep response fields flat: `id`, `timestamp`, `actor`, `action`, `resource`, `outcome`, `context`.
  - Use `nextCursor`, with `null` for empty results and the last page.

- [ ] 2. Add read repository for audit event queries
  - Add `AuditEventReadRepository` using `NamedParameterJdbcTemplate`.
  - Support optional filters: exact `actor`, escaped prefix `resource`, `[from, to)` timestamp range, exact `outcome`.
  - Support stable ordering by `timestamp`, then `id`, for both `asc` and `desc`.

- [ ] 3. Add cursor encoding and validation
  - Implement opaque URL-safe base64 cursor without padding.
  - Cursor payload contains timestamp, ULID id, sort direction, and SHA-256 hash of filters.
  - Reject invalid cursor, filter mismatch, and sort mismatch with HTTP 400.

- [ ] 4. Add query validation
  - Validate `from`, `to`, `outcome`, `sort`, `limit`, and cursor parse errors.
  - Reject `from > to` and `to - from > 90 days`.
  - Allow open-ended ranges.
  - Clamp `limit > 200` to 200; reject non-numeric or `< 1`.

- [ ] 5. Add query service
  - Add `AuditEventQueryService`.
  - Normalize timestamps to microsecond precision before comparison and cursor generation.
  - Fetch `limit + 1` rows to decide `nextCursor`.
  - Return only the requested page size.

- [ ] 6. Add GET /audit-events controller
  - Add `AuditEventQueryController`.
  - Bind query params to `AuditEventQuery`.
  - Return the exact response shape from the design document.
  - Secure the endpoint with the same Basic-auth role policy as the existing audit endpoints.

- [ ] 7. Verify and add required indexes
  - Verify required Postgres indexes for timestamp, actor, resource prefix, and outcome query paths.
  - Add a Flyway migration only for missing indexes.
  - Keep the existing write-side model and storage behavior unchanged.

- [ ] 8. Add integration tests for filters and sorting
  - Use Testcontainers with real Postgres.
  - Cover actor exact match, resource prefix match, timestamp `[from, to)` boundaries, and outcome filter.
  - Cover `sort=asc` and `sort=desc`.

- [ ] 9. Add integration tests for pagination stability
  - Seed multiple events, fetch page 1, insert newer events, then fetch page 2 using the cursor.
  - Assert stable order and no duplicate or skipped original rows.
  - Cover `nextCursor = null` on the last page and the empty result shape.

- [ ] 10. Add integration tests for validation errors
  - Cover all HTTP 400 cases from the design: invalid timestamps, cursor, outcome, sort, limit, range order, range over 90 days, cursor filter mismatch, and cursor sort mismatch.
  - Cover `limit=500` clamping to max 200 without error.

- [ ] 11. Add resource prefix escaping tests
  - Verify `%`, `_`, and `\` in client input are escaped for SQL `LIKE`.
  - Ensure wildcard-looking input does not broaden matches.

- [ ] 12. Update API documentation
  - Update the `CLAUDE.md` API section with `GET /audit-events`.
  - Reference `.specs/query-api/`.
  - Do not document out-of-scope observability or write-side changes.

