# Spec evaluation: query-api

| Axis | Score | Evidence | Gap to next level |
|------|-------|----------|-------------------|
| Testability of acceptance criteria | 2/3 | `requirements.md:24-35` — "Cursor pagination across multiple pages, stable order under inserts", "`limit` clamping to 200", "Response shape matches the example exactly". Each AC yields binary pass/fail. | Not EARS-phrased — no `WHEN`/`IF`/`THEN` clauses. Rewrite each AC as `WHEN <input> THEN <observable outcome>` and enumerate edge cases inline in requirements.md (currently spread into design.md test plan). |
| Determinism of design              | 3/3 | `design.md:15` sort tiebreaker `timestamp` then `id` (ULID); `design.md:59-64` keyset SQL `(timestamp,id) < (cursor.ts,cursor.id)`; `design.md:83-100` all 400 cases enumerated with parse vs semantic split; `design.md:103-111` four composite indexes named with justification ("Plain `timestamp` index alone insufficient for p95 < 300ms"); `design.md:72-74` microsecond truncation rule. | Already production-grade. |
| Decomposition quality of tasks     | 2/3 | `tasks.md:3` "One task = one safe commit"; `tasks.md:5-8` source docs referenced at top; `implementation-plan.md:9-14` per-task Success criteria act as DoD; 12 tasks, each scoped to one layer (types → repo → cursor → validation → service → controller → indexes → tests → docs). | Per-task dependencies are implicit (order only). No task names its predecessor explicitly (e.g. task 5 needs 2+3+4, task 6 needs 5, task 9 needs 6+8). Add explicit `Depends on:` line per task in `tasks.md` so a reader can prove safe-commit order without reading every task body. Also link each task to a specific section anchor (`design.md#pagination`) rather than the file as a whole. |

**Verdict:** SHIP
**Reason:** All three axes ≥ 2 (2 / 3 / 2). Determinism is production-grade; testability and decomposition meet the bar but stop short of 3.

**Optional improvements to reach 3/3/3:**
1. `requirements.md` — rewrite acceptance criteria in EARS form, e.g. `WHEN client sends GET /audit-events?from=T1&to=T2 with T1>T2 THEN server returns 400 with error code "invalid_range"`. Pull edge cases (boundary inclusivity, limit clamp, cursor mismatch) from `design.md` test plan into the AC list so requirements stands alone.
2. `tasks.md` — add `Depends on: [task N]` line under each task and replace top-level `Source documents` block with per-task section anchors (`requirements.md#acceptance-criteria`, `design.md#pagination`, `design.md#indexes`).
3. `tasks.md` — add per-task Definition of Done inline (currently only in `implementation-plan.md`); duplicating the DoD bullet next to the task makes the commit boundary self-contained for a reviewer reading only `tasks.md`.
