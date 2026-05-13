# Spec quality — three axes, 0 to 3

A shared rubric we apply to every spec before it lands in the repo.

**Minimum to ship into the repo: 2 / 2 / 2.**

---

## Axes

### 1. Testability of acceptance criteria

| Score | Label | Definition |
|-------|-------|------------|
| 0 | Missing | No acceptance criteria, or prose-only narrative. |
| 1 | Partial | AC exist; some are vague or non-verifiable. |
| 2 | Solid | Every AC is testable (binary pass/fail check possible). |
| 3 | Production-grade | [EARS-style](https://alistairmavin.com/ears/) (`WHEN` / `IF` / `WHILE` … `THEN` …), edge cases enumerated. |

### 2. Determinism of design

| Score | Label | Definition |
|-------|-------|------------|
| 0 | Missing | Sort order, pagination, error contract unspecified. |
| 1 | Partial | Contract present; tiebreakers missing. |
| 2 | Solid | Tiebreaker, pagination, status codes locked. |
| 3 | Production-grade | Every choice justified; indexes prove it. |

### 3. Decomposition quality of tasks

| Score | Label | Definition |
|-------|-------|------------|
| 0 | Missing | One big task, or no tasks at all. |
| 1 | Partial | Tasks exist; missing references / Definition of Done. |
| 2 | Solid | References, DoD, dependencies in place. |
| 3 | Production-grade | Each task = one safe commit; order is provable. |

---

## Evaluation form

For every spec under review, fill the table below. Quote concrete evidence (file:line or short excerpt) — never assign a score without it.

| Axis | Score (0–3) | Evidence | Gap to next level |
|------|-------------|----------|-------------------|
| Testability of acceptance criteria | | | |
| Determinism of design | | | |
| Decomposition quality of tasks | | | |

**Verdict:** SHIP / BLOCK (block if any axis < 2).

**Top fixes to unblock:**
1.
2.
3.
