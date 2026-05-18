# Spec evaluation: query-api (github.com/ulbartosh/audit-log-service)

Source: `https://github.com/ulbartosh/audit-log-service/tree/main/.specs/query-api`
Rubric: `.specs/_eval-checklist.md` (three axes, 0–3, ship bar 2/2/2)
Evaluated: 2026-05-18

| Axis | Score | Evidence | Gap to next level |
|------|-------|----------|-------------------|
| Testability of acceptance criteria | 3/3 | `requirements.md:71` — "If `from` is not a valid ISO-8601 instant, then the system shall return `400 Bad Request`." Explicit `WHEN`/`IF`…`THEN`. Edge cases enumerated: empty result `:69`, payload absent ≠ null `:83`, page beyond end `:98`, from-after-to `:75`. | At max. |
| Determinism of design | 3/3 | `design.md:9` — `ORDER BY occurred_at DESC, id DESC`, tiebreaker justified `:81`. Keyset cursor + version byte `:83-89`, next-page predicate `:94-95`, status table `:58-62`, 3 composite indexes justified `:139` ("index satisfies ORDER BY without a sort step"). | At max. |
| Decomposition quality of tasks | 3/3 | `tasks.md:13-18` dependency graph; each task has Branch/Refs/Scope/DoD/Dependencies (`:41-48`). Tasks 03-06 ship unused code → each commit safe/revertable. Coverage table `:227-245` maps every AC → step. | At max. |

**Verdict:** SHIP
**Reason:** All three axes 3/3. Rubric bar 2/2/2 cleared.

## Optional improvements to reach beyond 3/3/3 (none blocking)

1. `design.md:100` — "Filter consistency" mismatch (different filters + `pageToken`) is "documented, not enforced". Could harden: hash filters into cursor, reject mismatch with `400`. Trade-off intentional but worth a one-line rationale.
2. `requirements.md:116-162` — open questions all resolved in `design.md` table, but `requirements.md` itself still lists them as open. Add inline "→ resolved, see design #N" markers so requirements reads standalone.
3. `tasks.md:204` — task 07 deletes `PagedResponse` conditionally ("verify with `rg`"). Pre-resolve: state now whether other callers exist, so the task is not conditional.
