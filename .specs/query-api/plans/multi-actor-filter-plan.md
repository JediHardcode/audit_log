# Multi-Actor Filter Implementation Plan

Context: Query API is already implemented for one `actor` value. This plan is a
delta for extending the existing filter to comma-separated `actor` values with a
maximum of 10 distinct non-blank actors.

## 1. API query model and controller binding

Files:
- `src/main/java/kirill/ked/auditlog/api/AuditEventQuery.java`
- `src/main/java/kirill/ked/auditlog/api/AuditEventController.java`

Change:
- Keep `AuditEventQuery.actor: String` as the raw wire value, or rename it to `actorRaw` if that better matches local naming.
- Bind `GET /audit-events` as the existing single query parameter: `@RequestParam(required = false) String actor`.
- Keep wire parameter name `actor`; clients call `?actor=u_42` or `?actor=u_42,svc_billing`.
- Preserve backward compatibility for one actor value.

Acceptance:
- Single `actor` request still validates as a one-element actor list.
- Missing `actor` request validates as an empty list and means no actor filter.

## 2. Validation and actor normalization

Files:
- `src/main/java/kirill/ked/auditlog/domain/query/QueryValidator.java`
- `src/main/java/kirill/ked/auditlog/domain/query/ValidatedQuery.java`

Change:
- Add parsing and normalization for the raw actor string:
  - split on comma;
  - trim each token so `actor=a, b` behaves like `actor=a,b`;
  - ignore blank tokens;
  - remove duplicates;
  - sort remaining values for stable filter identity.
- Store normalized actors in `ValidatedQuery.actors: List<String>`.
- Enforce max size: if normalized distinct non-blank actor count is greater than 10, throw `InvalidQueryException("too_many_actors", "actor filter supports at most 10 values")`.
- Keep empty normalized list as no actor filter.

Acceptance:
- `actor=u_42,u_42` behaves like one actor.
- `actor=b,a` and `actor=a,b` produce the same `ValidatedQuery.actors`.
- 11 distinct non-blank comma-separated actors return HTTP 400.

## 3. Cursor filter hash

Files:
- `src/main/java/kirill/ked/auditlog/domain/query/FilterHash.java`
- `src/main/java/kirill/ked/auditlog/domain/AuditEventQueryService.java`
- `src/test/java/kirill/ked/auditlog/unit/CursorCodecTest.java`
- `src/test/java/kirill/ked/auditlog/unit/AuditEventQueryServiceTest.java`

Change:
- Replace `FilterHash.compute(String actor, ...)` with `FilterHash.compute(List<String> actors, ...)`.
- Canonical actor value for the hash must be order-independent and duplicate-independent.
- Use normalized actors from `ValidatedQuery`; do not normalize twice in service.
- Hash must differ when effective actor set differs.
- Existing single actor hash compatibility with old in-flight cursors is not required unless explicitly needed; cursor is opaque and tied to current query implementation.

Acceptance:
- Cursor created with `actor=a,b` remains valid for `actor=b,a`.
- Cursor created with `actor=a,b` is rejected for `actor=a,c`.
- Cursor created with duplicate actors is equivalent to cursor created with the same distinct actor set.

## 4. Repository SQL

Files:
- `src/main/java/kirill/ked/auditlog/persistence/AuditEventReadRepository.java`
- `src/test/java/kirill/ked/auditlog/integration/AuditEventReadRepositoryTest.java`

Change:
- Replace repository `String actor` parameter with `List<String> actors`.
- If actors list is non-empty, add `actor IN (:actors)` to the SQL.
- Keep no actor predicate when actors list is empty.
- Keep all existing filters, ordering, keyset cursor, resource escaping, and `limit` behavior unchanged.

Acceptance:
- One actor value returns only that actor.
- Multiple actor values return rows for any actor in the list.
- Rows for actors outside the list are excluded.
- Existing `(actor, timestamp DESC, id DESC)` index remains useful because max actor set is bounded to 10.

## 5. Integration tests for HTTP behavior

Files:
- `src/test/java/kirill/ked/auditlog/integration/AuditEventQueryFilterIT.java`
- `src/test/java/kirill/ked/auditlog/integration/AuditEventQueryValidationIT.java`

Add coverage:
- `GET /audit-events?actor=u_42,svc_billing` returns only those two actors.
- Single actor test still passes unchanged or with minimal assertion update.
- Duplicate actors do not duplicate rows or change result set.
- Same actor set in different order works with cursor reuse.
- More than 10 distinct non-blank comma-separated actors returns 400.
- More than 10 raw comma-separated tokens with duplicates but 10 or fewer distinct non-blank actors does not return 400.

Acceptance:
- Requirements AC-F1, AC-F1a, AC-F1b, AC-F1c, AC-F1d, AC-E9, and AC-E11 are covered.

## 6. Unit tests for validation and hash canonicalization

Files:
- `src/test/java/kirill/ked/auditlog/unit/QueryValidatorTest.java`
- Add `src/test/java/kirill/ked/auditlog/unit/FilterHashTest.java` if no suitable test exists.

Add coverage:
- Blank actor values are ignored.
- Duplicate actor values collapse to one.
- Actor values are sorted in `ValidatedQuery`.
- Exactly 10 distinct actors is valid.
- 11 distinct actors throws `InvalidQueryException` with code `too_many_actors`.
- Filter hash is equal for same effective actor set in different order.
- Filter hash differs for different actor sets.

## 7. Verification

Run:

```bash
./gradlew spotless
./gradlew test
```

Acceptance:
- All existing Query API behavior remains green.
- New multi-actor acceptance criteria are green.
- No changes to POST `/audit-events`, event model, hash chain, retention, or database schema.
