---
name: spec-eval
description: Evaluate a spec (requirements / design / tasks) against the repo's three-axis quality rubric (testability, determinism, decomposition) on a 0–3 scale. Use when the user asks to "evaluate the spec", "score this spec", "is this spec ready to ship", "review .specs/<name>", or invokes /spec-eval. Outputs per-axis score with evidence, gap-to-next-level, and a SHIP / BLOCK verdict against the 2/2/2 minimum.
---

# spec-eval

Evaluate a spec against the shared rubric defined in `.specs/_eval-checklist.md`.
Three axes, scored independently from 0 (missing) to 3 (production-grade).
Minimum bar to ship into the repo: **2 / 2 / 2**.

## When to invoke

- User says: "evaluate the spec", "score this spec", "rate spec quality", "is `<spec>` ready?", "review `.specs/<name>`", "/spec-eval".
- Before merging anything under `.specs/`.

## Inputs

- **Required:** path to the spec — either a directory (e.g. `.specs/query-api/`) containing `requirements.md`, `design.md`, `tasks.md`, or a single file.
- **Optional:** focus axis (`testability` | `determinism` | `decomposition`) if the user only wants one axis scored.

If the path is missing or ambiguous, ask the user before scoring.

## Procedure

1. **Load the rubric.** Read `.specs/_eval-checklist.md`. This is the single source of truth — never invent score definitions.
2. **Read the spec.** Read every file in the target directory (or the single file). Do not skim.
3. **Score each axis independently.** For each of the three axes:
   - Pick the highest score whose definition the spec *fully* satisfies. If only partially satisfied, drop one level.
   - Cite concrete evidence: `file:line` reference or a short verbatim excerpt. **No score without evidence.**
   - State the gap to the next level in one sentence.
4. **Compute verdict.** `SHIP` if every axis ≥ 2. Otherwise `BLOCK`.
5. **List top fixes.** 1–3 concrete edits that would raise the lowest axis by one level. Each fix names the file and what to add/change.
6. **Output.** Use the exact format below. Do not add prose outside it.

## Scoring guidance (anti-grade-inflation)

- **Testability:** an AC is testable only if a reviewer can write a binary pass/fail check from it alone. "User can search by actor" — not testable. "GET /audit-events?actor=X returns 200 with only events where actor=X, sorted by timestamp DESC" — testable. EARS = explicit `WHEN`/`IF`/`WHILE` … `THEN` … phrasing.
- **Determinism:** check for sort key + tiebreaker, pagination contract (cursor or offset + limits), error response shape, HTTP status codes for each failure mode, index strategy. Missing any of these caps the score at 1.
- **Decomposition:** each task must have (a) a reference to the requirement/design section it implements, (b) a Definition of Done, (c) explicit dependencies on other tasks. "Each task = one safe commit" means: revertable in isolation without breaking main.

When in doubt between two scores, pick the lower one and state the doubt in the gap column.

## Output format

```markdown
# Spec evaluation: <spec name>

| Axis | Score | Evidence | Gap to next level |
|------|-------|----------|-------------------|
| Testability of acceptance criteria | N/3 | `file:line` — "…" | … |
| Determinism of design              | N/3 | `file:line` — "…" | … |
| Decomposition quality of tasks     | N/3 | `file:line` — "…" | … |

**Verdict:** SHIP / BLOCK
**Reason:** <one line — which axes failed the 2/2/2 bar, or why all pass>

**Top fixes to unblock:**
1. `<file>` — <concrete edit>
2. `<file>` — <concrete edit>
3. `<file>` — <concrete edit>
```

If `SHIP`, the "Top fixes" section becomes "Optional improvements to reach 3/3/3" (still concrete, but not blocking).

## Boundaries

- Do **not** edit the spec while evaluating. Read-only pass.
- Do **not** score axes the user excluded via focus parameter.
- Do **not** invent rubric levels — quote the definitions from `references/_eval-checklist.md`.
- If the spec is split across multiple files and a file is missing (e.g. no `tasks.md`), score decomposition based on what exists and note the missing file as evidence for the score.
