# Query API Tasks

One task = one safe commit. Each task is revertable in isolation without breaking main.

## Status legend (for the AI agent executing this spec)

- `[ ]` not started
- `[~]` partially present in repo — code exists but **diverges from spec**; must be reshaped/replaced, not extended
- `[x]` done — matches spec, no work needed

### Rule for handling pre-existing code

Spec wins. For each existing class touched by this work:
- If the **name** still fits the spec role → keep the name, reshape the body to match the spec.
- If the role is gone → delete the file. No "kept for backwards-compat" leftovers.
- Write-side code (`POST /audit-events`, hash chain, append-only triggers) is **out of scope** — touch only where the read path forces it (e.g. trimming response DTO fields that the write path also returns).

### Pre-existing code inventory + disposition

1. **`AuditEventResponse`** (`src/main/java/.../api/AuditEventResponse.java`)
   - Today: `id (UUID), timestamp, actor, action, resource, outcome, context, prevHash, eventHash`. Used by POST and current GET.
   - Spec role (`design.md#layering--classes`): `AuditEventDto` with flat `id, timestamp, actor, action, resource, outcome, context`.
   - **Disposition: reuse name `AuditEventResponse` as the spec's `AuditEventDto`.** Drop `prevHash` / `eventHash` from the DTO — hash chain is an internal tamper-evidence detail (`CLAUDE.md` architectural rules) and is not in the read-API contract. POST response shape changes accordingly: this is acceptable because the spec is priority, and the hash fields were never part of an external contract.
2. **`PagedResponse<T>`** (`src/main/java/.../api/PagedResponse.java`)
   - Today: generic offset-page wrapper (`content / page / size / totalElements`).
   - Spec role: cursor page `{ items, nextCursor }` — different shape, different generic-vs-specific intent.
   - **Disposition: delete `PagedResponse`.** Add new `AuditEventPage` (spec-named) with `items` + `nextCursor`. No other endpoint uses `PagedResponse`.
3. **`AuditEventQuery`** — does not exist. Add new per spec.
4. **`AuditEventRepository`** (`src/main/java/.../persistence/AuditEventRepository.java`)
   - Today: JPA `Repository` with `save`, `findById`, `findLatest`, `acquireInsertLock`, `findAllForChainVerification`, `findAll(Specification, Pageable)`.
   - Spec role: only the **read** path is in scope. Write path stays JPA.
   - **Disposition: keep the file, delete the `findAll(Specification, Pageable)` method** (only used by the read path). Add a **new** `AuditEventReadRepository` (spec-named) using `NamedParameterJdbcTemplate` + `RowMapper`.
5. **`AuditEventEntity`** (JPA entity) — keep as-is, used by write path only after step 4.
6. **`AuditEventService.search(...)`** (`src/main/java/.../domain/AuditEventService.java:74`)
   - Today: offset-paginated, JPA-based, returns `PagedResponse`.
   - Spec role: replaced by `AuditEventQueryService`.
   - **Disposition: delete `search(...)` and its private `buildSpec(...)`.** Keep `create(...)`. Also delete the now-unused JPA imports (`jakarta.persistence.criteria.Predicate`, `Page`, `PageRequest`, `Sort`, `Specification`).
7. **`AuditEventController`** (`src/main/java/.../api/AuditEventController.java`)
   - Today: class has `POST` + `GET` (old shape).
   - **Disposition: keep the class and the `POST` handler. Replace the `GET` method** (`search(actor, resource, from, to, page, size)`) with the spec signature. Remove `@Max(500)` and the offset params. Route to the new `AuditEventQueryService`.
8. **`GlobalExceptionHandler` / `ErrorResponse`** — reuse as-is for the new 400 cases.
9. **`id` type — UUID vs ULID**
   - Today: column is `UUID`. Spec `design.md` says ULID.
   - **Disposition: reuse UUID.** UUIDs sort lexicographically and provide a stable tiebreaker; the timestamp gives the time order, `id` only resolves ties. Migrating to ULID would touch the write path (out of scope) and the existing chain. Update `design.md` `### Identifiers` to say UUID as part of task 1.
10. **Indexes** (`V1__init.sql`)
    - Today: single-column `idx_audit_events_actor`, `idx_audit_events_resource`, `idx_audit_events_timestamp (timestamp DESC)`.
    - Spec: four composites including `(timestamp DESC, id DESC)`, `(resource text_pattern_ops, ...)`, `(outcome, ...)`.
    - **Disposition:** add new Flyway migration `V3__query_api_indexes.sql` creating the composites. Drop the redundant single-column indexes in the same migration (`actor`, `resource`, `timestamp`) — they're strict subsets of the new composites. Do not edit `V1`/`V2`.
11. **`AuditEventIntegrationTest`** (`src/test/java/.../integration/AuditEventIntegrationTest.java`)
    - References old `PagedResponse.getContent()` and `?page=1&size=50` in `postAndGet_happyPath` and `pagination_secondPage`.
    - **Disposition: rewrite both to the new `items` / `nextCursor` / `limit` shape.** Keep the Testcontainers fixture (the `postgres:16` container + `@DynamicPropertySource` + `TRUNCATE` `@BeforeEach`); extract a base class if helpful for the new test files.
12. **Spring Security / auth** — no `SecurityFilterChain` or Spring Security dep present today. **Decision (2026-05-11): Basic auth is in scope for this spec.** Add `spring-boot-starter-security` to `build.gradle`, add a `SecurityFilterChain` config covering both POST and GET `/audit-events`, define roles per `design.md#auth`. Existing integration tests must add a Basic-auth header (or use `TestRestTemplate.withBasicAuth`).

## Tasks

- [x] **1. Add query API response and request types**
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
  - **Repo status:**
    - Reuse `AuditEventResponse` name as the spec's `AuditEventDto`. Strip `prevHash`/`eventHash`. POST response shape changes — acceptable per spec priority. Update `from(AuditEventEntity)` to skip the hash fields.
    - Delete `PagedResponse<T>`. Add new `AuditEventPage { items, nextCursor }`.
    - Add new `AuditEventQuery` (request POJO with `@Builder`).
    - Update `design.md` `### Identifiers` from ULID to UUID (resolves the id-type conflict — see disposition #9).

- [x] **2. Add read repository for audit event queries**
  - **Refs:** [design.md#layering--classes](./design.md#layering--classes), [design.md#resource-prefix-matching](./design.md#resource-prefix-matching), AC-F1..AC-F7, AC-P1, AC-P2
  - **Depends on:** 1
  - **Scope:**
    - Add `AuditEventReadRepository` using `NamedParameterJdbcTemplate`. No JPA, no entities.
    - Optional filters: exact `actor`, escaped prefix `resource`, `[from, to)` timestamp range, exact `outcome`.
    - Order by `timestamp` then `id` for both `asc` and `desc`.
  - **DoD:**
    - Repository unit-level test (Testcontainers) covers each filter in isolation and both sort directions.
    - SQL uses `LIKE :prefix || '%' ESCAPE '\'` for resource.
  - **Repo status:**
    - Add new `AuditEventReadRepository` (`NamedParameterJdbcTemplate` + `RowMapper` → `AuditEventResponse`).
    - Delete `findAll(Specification, Pageable)` method from existing `AuditEventRepository` — only the write methods (`save`, `findById`, `findLatest`, `acquireInsertLock`, `findAllForChainVerification`) remain.
    - Do NOT touch `AuditEventEntity` — still used by write path.

- [x] **3. Add cursor encoding and validation**
  - **Refs:** [design.md#pagination](./design.md#pagination), [design.md#cursor-invalidation](./design.md#cursor-invalidation), AC-E8, AC-E9, AC-E10
  - **Depends on:** 1
  - **Scope:**
    - Cursor: URL-safe base64 without padding of JSON `{"ts","id","s","f"}`.
    - Filter hash: SHA-256 hex over canonical `actor|resource|from|to|outcome`.
    - Decode rejects bad base64, bad JSON, sort mismatch, filter-hash mismatch → 400.
  - **DoD:**
    - Unit tests cover round-trip encode/decode, each of the three 400 cases (AC-E8, AC-E9, AC-E10).
    - Cursor is opaque — no field of the encoded payload leaks unparsed.
  - **Repo status:** nothing exists. Cursor `id` field encodes UUID as string (see disposition #9).

- [x] **4. Add query validation**
  - **Refs:** [design.md#validation--errors-all-400](./design.md#validation--errors-all-400), AC-E1..AC-E7, AC-P7, AC-P8
  - **Depends on:** 1
  - **Scope:**
    - Parse: invalid `from`/`to`/`outcome`/`sort`/`limit` → 400.
    - Semantic: `from > to` → 400; `to - from > 90 days` → 400; open-ended ranges allowed.
    - `limit > 200` silently clamped to 200; default 50; `limit < 1` → 400.
  - **DoD:**
    - Unit tests cover each AC-E1..AC-E7 case and AC-P7 clamp + AC-P8 default.
  - **Repo status:**
    - Reuse `GlobalExceptionHandler` + `ErrorResponse` for all 400 responses.
    - `@Max(500)` on the current `size` param is gone after task 6 (param itself is removed). Do not carry it over to `limit` — clamp in code, do not reject.
    - From/to ISO parsing via Spring `@DateTimeFormat` already raises 400 through the existing handler — reuse mechanism for AC-E1.

- [x] **5. Add query service**
  - **Refs:** [design.md#layering--classes](./design.md#layering--classes), [design.md#timestamp-precision](./design.md#timestamp-precision), AC-P3, AC-P4, AC-P5
  - **Depends on:** 2, 3, 4
  - **Scope:**
    - `AuditEventQueryService`: validate, decode cursor, fetch `limit + 1`, build `nextCursor` from last returned item when extra row exists.
    - Normalize timestamps to microsecond precision before comparison and cursor encoding.
  - **DoD:**
    - Service unit test: `nextCursor != null` iff extra row was fetched (AC-P3); `nextCursor == null` for last page and empty result (AC-P4, AC-P5).
    - Nanosecond Instant input produces same cursor as the microsecond-truncated value.
  - **Repo status:**
    - Add new `AuditEventQueryService`.
    - Delete `AuditEventService.search(...)` and its private `buildSpec(...)` from `domain/AuditEventService.java`. Strip the now-dead JPA imports (`Predicate`, `Page`, `PageRequest`, `Sort`, `Specification`). Keep `create(...)` untouched.
    - Add a `Clock` bean (e.g. in a `@Configuration` class) and inject it here — required by task 9 for deterministic seeding.

- [x] **6. Add GET /audit-events controller**
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
  - **Repo status:**
    - Replace `AuditEventController.search(...)` at `AuditEventController.java:36`. New signature: `actor, resource, from, to, outcome, sort, limit, cursor` → `AuditEventPage`. Drop `@Max(500)` and the offset params. Return `AuditEventPage`, not `PagedResponse`. Keep `@PostMapping create(...)` untouched (except its response type — see task 1).
    - **Auth (decided 2026-05-11):** add Basic auth as part of this task. Steps:
      - Add `org.springframework.boot:spring-boot-starter-security` dep in `build.gradle`.
      - Add `SecurityConfig` (`@Configuration`) with a `SecurityFilterChain` bean. `httpBasic()`, CSRF disabled for the API, `/audit-events` requires authenticated role(s) per `design.md#auth`. Stateless session.
      - Define test users (in-memory `UserDetailsService` or properties-driven). Real user store is out of scope.
      - Existing integration tests must authenticate: use `TestRestTemplate.withBasicAuth(...)` on every request, or default credentials via `spring.security.user.*`.
      - Add a dedicated test: unauthenticated GET returns 401 (matches AC for "Unauthenticated request rejected with same status as POST endpoint").
      - Same `SecurityFilterChain` covers POST too — write-side integration tests will need the auth header.

- [x] **7. Verify and add required indexes**
  - **Refs:** [design.md#indexes](./design.md#indexes), non-functional p95 < 300ms
  - **Depends on:** 2
  - **Scope:**
    - Verify presence of `(timestamp DESC, id DESC)`, `(actor, timestamp DESC, id DESC)`, `(resource text_pattern_ops, timestamp DESC, id DESC)`, `(outcome, timestamp DESC, id DESC)`.
    - Add Flyway migration only for missing indexes — schema-only change, no data mutation (append-only preserved).
  - **DoD:**
    - Integration test runs `EXPLAIN` (or asserts `pg_indexes`) showing each filter path uses a composite index, not a seq scan.
    - No write-side storage behavior changed.
  - **Repo status:**
    - Existing single-column indexes (`idx_audit_events_actor`, `idx_audit_events_resource`, `idx_audit_events_timestamp`) become strict subsets of the spec composites — drop them in the same `V3__query_api_indexes.sql` migration.
    - New migration creates: `(timestamp DESC, id DESC)`, `(actor, timestamp DESC, id DESC)`, `(resource text_pattern_ops, timestamp DESC, id DESC)`, `(outcome, timestamp DESC, id DESC)`. Do not edit V1/V2.

- [x] **8. Add integration tests for filters and sorting**
  - **Refs:** AC-F1..AC-F7, AC-P1, AC-P2
  - **Depends on:** 6, 7
  - **Scope:**
    - Testcontainers + real Postgres.
    - Cover actor exact, resource prefix, `[from, to)` boundaries (AC-F4, AC-F5), outcome filter, `sort=asc`, `sort=desc`.
  - **DoD:**
    - Every AC-F* and AC-P1/AC-P2 has at least one assertion referencing it (comment or test name).
  - **Repo status:**
    - Reuse the Testcontainers fixture from `AuditEventIntegrationTest` (`postgres:16`, `@DynamicPropertySource`, `TRUNCATE` `@BeforeEach`). Extract a base class.
    - Delete `pagination_secondPage` — old offset model, replaced by tasks 8/9 with cursor coverage.
    - Rewrite `postAndGet_happyPath` to assert the new `items` + `nextCursor` shape (and the new `AuditEventResponse` without hash fields).

- [x] **9. Add integration tests for pagination stability**
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
  - **Repo status:**
    - No `Clock` bean today. Spec's pagination test needs deterministic timestamps. Seed events for this test via direct `JdbcTemplate` inserts (pattern already used by `auditEventsTable_rejectsUpdate`) so the test controls timestamps without refactoring `AuditEventService.create`.

- [x] **10. Add integration tests for validation errors**
  - **Refs:** AC-E1..AC-E10, AC-P7
  - **Depends on:** 6
  - **Scope:**
    - One test per AC-E* case + AC-P7 clamp.
    - Cursor mismatch tests: fetch page with `actor=A`, reuse cursor with `actor=B` → 400 (AC-E9); fetch with `sort=desc`, reuse with `sort=asc` → 400 (AC-E10).
  - **DoD:**
    - Every AC-E1..AC-E10 has at least one failing-input test returning 400.
    - AC-P7 covered with `limit=500` returning ≤200 items, status 200.
  - **Repo status:** no GET 400 tests exist. Reuse `ErrorResponse` body shape for assertions (same handler).

- [ ] **11. Add resource prefix escaping tests**
  - **Refs:** [design.md#resource-prefix-matching](./design.md#resource-prefix-matching), AC-X1, AC-X2, AC-X3
  - **Depends on:** 8
  - **Scope:**
    - Insert resource literally `order/100%off`; query `resource=order/100\%` matches; query `resource=order/100` matches (prefix).
    - Query `resource=order/_` does NOT match unrelated rows via wildcard.
  - **DoD:**
    - AC-X1, AC-X2, AC-X3 each covered by named test.
  - **Repo status:** none. Depends on task 2 already using `LIKE ... ESCAPE '\'`.

- [~] **12. Update API documentation**
  - **Refs:** [design.md#documentation-update](./design.md#documentation-update)
  - **Depends on:** 6
  - **Scope:**
    - Update `CLAUDE.md` `## API` section: `GET /audit-events` line referencing `.specs/query-api/`.
  - **DoD:**
    - `CLAUDE.md` API section lists GET `/audit-events` with cursor pagination note.
    - No out-of-scope observability or write-side changes documented.
  - **Repo status:** edit existing `GET /audit-events` line under `## API` in `CLAUDE.md`. No duplicate line.

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
