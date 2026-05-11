# Query API Tasks

One task = one safe commit. Each task is revertable in isolation without breaking main.

## Tasks

- [ ] **1. Add query API response and request types**
  - **Refs:** [requirements.md#response-shape](./requirements.md#response-shape), [design.md#layering--classes](./design.md#layering--classes)
  - **Depends on:** none
  - **Scope:**
    - Add `AuditEventQuery`, `AuditEventDto`, `AuditEventPage`.
    - Response fields flat: `id`, `timestamp`, `actor`, `action`, `resource`, `outcome`, `context`.
    - `nextCursor` field on page wrapper, nullable.
  - **DoD:**
    - Types compile.
    - No controller, repository, or SQL behavior added in this commit.
    - Unit test asserts `AuditEventPage` serializes to the exact JSON shape from `design.md#response` (with `nextCursor: null` when empty).

- [ ] **2. Add read repository for audit event queries**
  - **Refs:** [design.md#layering--classes](./design.md#layering--classes), [design.md#resource-prefix-matching](./design.md#resource-prefix-matching), AC-F1..AC-F7, AC-P1, AC-P2
  - **Depends on:** 1
  - **Scope:**
    - Add `AuditEventReadRepository` using `NamedParameterJdbcTemplate`. No JPA, no entities.
    - Optional filters: exact `actor`, escaped prefix `resource`, `[from, to)` timestamp range, exact `outcome`.
    - Order by `timestamp` then `id` for both `asc` and `desc`.
  - **DoD:**
    - Repository unit-level test (Testcontainers) covers each filter in isolation and both sort directions.
    - SQL uses `LIKE :prefix || '%' ESCAPE '\'` for resource.

- [ ] **3. Add cursor encoding and validation**
  - **Refs:** [design.md#pagination](./design.md#pagination), [design.md#cursor-invalidation](./design.md#cursor-invalidation), AC-E8, AC-E9, AC-E10
  - **Depends on:** 1
  - **Scope:**
    - Cursor: URL-safe base64 without padding of JSON `{"ts","id","s","f"}`.
    - Filter hash: SHA-256 hex over canonical `actor|resource|from|to|outcome`.
    - Decode rejects bad base64, bad JSON, sort mismatch, filter-hash mismatch → 400.
  - **DoD:**
    - Unit tests cover round-trip encode/decode, each of the three 400 cases (AC-E8, AC-E9, AC-E10).
    - Cursor is opaque — no field of the encoded payload leaks unparsed.

- [ ] **4. Add query validation**
  - **Refs:** [design.md#validation--errors-all-400](./design.md#validation--errors-all-400), AC-E1..AC-E7, AC-P7, AC-P8
  - **Depends on:** 1
  - **Scope:**
    - Parse: invalid `from`/`to`/`outcome`/`sort`/`limit` → 400.
    - Semantic: `from > to` → 400; `to - from > 90 days` → 400; open-ended ranges allowed.
    - `limit > 200` silently clamped to 200; default 50; `limit < 1` → 400.
  - **DoD:**
    - Unit tests cover each AC-E1..AC-E7 case and AC-P7 clamp + AC-P8 default.

- [ ] **5. Add query service**
  - **Refs:** [design.md#layering--classes](./design.md#layering--classes), [design.md#timestamp-precision](./design.md#timestamp-precision), AC-P3, AC-P4, AC-P5
  - **Depends on:** 2, 3, 4
  - **Scope:**
    - `AuditEventQueryService`: validate, decode cursor, fetch `limit + 1`, build `nextCursor` from last returned item when extra row exists.
    - Normalize timestamps to microsecond precision before comparison and cursor encoding.
  - **DoD:**
    - Service unit test: `nextCursor != null` iff extra row was fetched (AC-P3); `nextCursor == null` for last page and empty result (AC-P4, AC-P5).
    - Nanosecond Instant input produces same cursor as the microsecond-truncated value.

- [ ] **6. Add GET /audit-events controller**
  - **Refs:** [design.md#endpoint](./design.md#endpoint), [design.md#auth](./design.md#auth), AC-R1, AC-R2
  - **Depends on:** 5
  - **Scope:**
    - `AuditEventQueryController` `@GetMapping("/audit-events")`.
    - Binds query params to `AuditEventQuery`.
    - Reuses existing Basic-auth role policy from POST `/audit-events`.
  - **DoD:**
    - MVC slice or integration test: 200 response matches the exact JSON shape (AC-R1, AC-R2).
    - Unauthenticated request rejected with same status as POST endpoint.
    - POST `/audit-events` behavior unchanged (existing tests still pass).

- [ ] **7. Verify and add required indexes**
  - **Refs:** [design.md#indexes](./design.md#indexes), non-functional p95 < 300ms
  - **Depends on:** 2
  - **Scope:**
    - Verify presence of `(timestamp DESC, id DESC)`, `(actor, timestamp DESC, id DESC)`, `(resource text_pattern_ops, timestamp DESC, id DESC)`, `(outcome, timestamp DESC, id DESC)`.
    - Add Flyway migration only for missing indexes — schema-only change, no data mutation (append-only preserved).
  - **DoD:**
    - Integration test runs `EXPLAIN` (or asserts `pg_indexes`) showing each filter path uses a composite index, not a seq scan.
    - No write-side storage behavior changed.

- [ ] **8. Add integration tests for filters and sorting**
  - **Refs:** AC-F1..AC-F7, AC-P1, AC-P2
  - **Depends on:** 6, 7
  - **Scope:**
    - Testcontainers + real Postgres.
    - Cover actor exact, resource prefix, `[from, to)` boundaries (AC-F4, AC-F5), outcome filter, `sort=asc`, `sort=desc`.
  - **DoD:**
    - Every AC-F* and AC-P1/AC-P2 has at least one assertion referencing it (comment or test name).

- [ ] **9. Add integration tests for pagination stability**
  - **Refs:** AC-P3, AC-P4, AC-P5, AC-P6
  - **Depends on:** 6, 7
  - **Scope:**
    - Inject controllable `Clock` bean.
    - Seed N events, fetch page 1, insert M newer events, fetch page 2 via cursor.
    - Assert page 2 contains only originally-seeded older events; no duplicates, no skips.
    - Cover empty result shape and last-page `nextCursor = null`.
  - **DoD:**
    - AC-P6 explicitly asserted with insert-between-pages step.
    - AC-P4 and AC-P5 each have a dedicated test.

- [ ] **10. Add integration tests for validation errors**
  - **Refs:** AC-E1..AC-E10, AC-P7
  - **Depends on:** 6
  - **Scope:**
    - One test per AC-E* case + AC-P7 clamp.
    - Cursor mismatch tests: fetch page with `actor=A`, reuse cursor with `actor=B` → 400 (AC-E9); fetch with `sort=desc`, reuse with `sort=asc` → 400 (AC-E10).
  - **DoD:**
    - Every AC-E1..AC-E10 has at least one failing-input test returning 400.
    - AC-P7 covered with `limit=500` returning ≤200 items, status 200.

- [ ] **11. Add resource prefix escaping tests**
  - **Refs:** [design.md#resource-prefix-matching](./design.md#resource-prefix-matching), AC-X1, AC-X2, AC-X3
  - **Depends on:** 8
  - **Scope:**
    - Insert resource literally `order/100%off`; query `resource=order/100\%` matches; query `resource=order/100` matches (prefix).
    - Query `resource=order/_` does NOT match unrelated rows via wildcard.
  - **DoD:**
    - AC-X1, AC-X2, AC-X3 each covered by named test.

- [ ] **12. Update API documentation**
  - **Refs:** [design.md#documentation-update](./design.md#documentation-update)
  - **Depends on:** 6
  - **Scope:**
    - Update `CLAUDE.md` `## API` section: `GET /audit-events` line referencing `.specs/query-api/`.
  - **DoD:**
    - `CLAUDE.md` API section lists GET `/audit-events` with cursor pagination note.
    - No out-of-scope observability or write-side changes documented.

## Verification

Run after each task:

```bash
./gradlew spotless test
```

## Dependency graph

```
1 ──┬── 2 ──┬── 5 ── 6 ──┬── 8 ── 11
    ├── 3 ──┤           ├── 9
    └── 4 ──┘           ├── 10
              7 ────────┤
                        └── 12
```

## Assumptions

- One task maps to one safe commit (revertable without breaking main).
- `GET /audit-events` reuses existing Basic-auth role policy from current audit endpoints.
- No changes to `POST /audit-events`, event model, field names, or write-side behavior.
- Storage changes limited to missing query indexes, if any.
