# Evidence: spec-eval block gate fired

Record of the spec-eval Stop hook detecting an unsafe `.specs` change and
blocking the turn from ending. Kept as proof the gate works.

## Date

2026-05-18

## What happened

1. **Spec edit** — the `## Acceptance criteria` section was deleted from
   `.specs/query-api/requirements.md` (all subsections: Filtering, Sorting and
   pagination, Validation errors, Resource prefix escaping, Response shape,
   Test coverage requirement, Multi-actor semantics).

2. **Stop hook fired** — the spec-eval gate blocked the turn with:

   > spec-eval gate blocked this turn. A .specs change is not ready to land.
   > Fix each item below, then run the spec-eval skill so it writes a fresh
   > review.md with a SHIP verdict. Do not end the turn until the gate passes.
   > - .specs/query-api: review.md is stale (spec edited after last eval) —
   >   re-run the spec-eval skill on .specs/query-api (it writes
   >   .specs/query-api/review.md)

3. **Re-evaluation** — running the spec-eval skill on the edited spec produced
   verdict **BLOCK**:
   - Testability of acceptance criteria: **0/3** — `requirements.md` had no
     acceptance criteria left; prose-only narrative (rubric score 0).
   - Determinism of design: 3/3 (unchanged).
   - Decomposition quality of tasks: 2/3 (unchanged), but every `tasks.md`
     `Refs: AC-*` was orphaned — pointing at deleted AC IDs.
   - Fails the 2/2/2 minimum bar → the `.specs` change cannot land.

4. **Resolution** — the deletion was reverted. `requirements.md` and
   `review.md` were restored via `git checkout`. The spec returned to its
   prior state; `review.md` verdict is **SHIP** (3/3/2) and is newer than all
   spec files, so the gate passes.

## Conclusion

The spec-eval block hook worked as intended: it caught a `.specs` edit that
dropped the spec below the repo quality bar (Testability 0/3) and prevented
the turn from ending until the spec was made shippable again.
