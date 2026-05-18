#!/usr/bin/env python3
"""Spec-eval lifecycle gate for Claude Code, Codex, and compatible agents.

Two-stage gate for changes under `.specs/`:

1. PostToolUse (`track` mode) records which `.specs/<feature>` folders a turn
   touched.
2. Stop (`stop` mode) gates the turn. For every touched feature it runs a
   cheap, deterministic structural pre-check, then requires a fresh spec-eval
   report (`.specs/<feature>/review.md`) with a `SHIP` verdict. Anything else
   blocks the turn and tells the agent to run the `spec-eval` skill.

The hook never scores the spec itself — scoring is the skill's job. The hook
only checks that the skill ran, ran *after* the latest spec edit, and shipped.
The agent's escape hatch is always the same: run the spec-eval skill, which
writes a fresh `review.md`.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import sys
import tempfile
import time
from pathlib import Path
from typing import Any


STATE_ROOT = Path(tempfile.gettempdir()) / "agent-spec-eval-hooks"
SPEC_PATH_RE = re.compile(r"(?:^|[\s\"'=:])((?:[A-Za-z]:)?/?[^\s\"']*?\.specs/([^/\s\"']+)/[^\s\"']+)")
TOKEN_RE = re.compile(r"[^A-Za-z0-9_.-]+")
VERDICT_RE = re.compile(r"verdict\b.{0,16}?\b(SHIP|BLOCK)\b", re.IGNORECASE)
REASON_RE = re.compile(r"reason\b.{0,4}?:?\**\s*(.+)", re.IGNORECASE)

SKILL_REL_PATH = Path(".claude/.skills/spec-eval")
SPEC_FILES = ("requirements.md", "design.md", "tasks.md")
REVIEW_FILE = "review.md"


def read_hook_input() -> dict[str, Any]:
    raw = sys.stdin.read().strip()
    return json.loads(raw) if raw else {}


def repo_root(payload: dict[str, Any]) -> Path:
    cwd = payload.get("cwd") or payload.get("workspaceRoot") or os.environ.get("PWD") or os.getcwd()
    return Path(str(cwd)).resolve()


def state_file(root: Path, payload: dict[str, Any]) -> Path:
    session_id = str(
        payload.get("session_id")
        or payload.get("sessionId")
        or payload.get("conversation_id")
        or os.environ.get("CODEX_SESSION_ID")
        or os.environ.get("CLAUDE_SESSION_ID")
        or "no-session"
    )
    repo_hash = hashlib.sha256(str(root).encode("utf-8")).hexdigest()[:16]
    return STATE_ROOT / f"{repo_hash}-{TOKEN_RE.sub('_', session_id)[:96]}.json"


def iter_strings(value: Any) -> list[str]:
    if isinstance(value, str):
        return [value]
    if isinstance(value, dict):
        items: list[str] = []
        for key, child in value.items():
            items.extend(iter_strings(key))
            items.extend(iter_strings(child))
        return items
    if isinstance(value, list):
        items = []
        for child in value:
            items.extend(iter_strings(child))
        return items
    return []


def touched_features(payload: dict[str, Any]) -> set[str]:
    """Extract every `.specs/<feature>` folder name mentioned in the payload."""
    haystacks = iter_strings(payload)
    haystacks.append(json.dumps(payload, ensure_ascii=False, default=str))
    features: set[str] = set()
    for text in haystacks:
        for match in SPEC_PATH_RE.finditer(text.replace("\\", "/")):
            feature = match.group(2).strip()
            if feature and not feature.startswith("_"):
                features.add(feature)
    return features


def load_state(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {"features": [], "updated_at": 0}
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {"features": [], "updated_at": 0}


def save_state(path: Path, features: set[str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps({"features": sorted(features), "updated_at": int(time.time())}, indent=2) + "\n")


def clear_state(root: Path, payload: dict[str, Any]) -> None:
    state_file(root, payload).unlink(missing_ok=True)


def evaluate_feature(root: Path, feature: str) -> list[str]:
    """Gate one feature. Return blocking reasons; empty list means SHIP.

    Stage 1 — structural pre-check (cheap, deterministic): the spec folder must
    contain non-empty spec files. Stage 2 — the spec-eval skill must have run:
    `review.md` must exist, be newer than every spec file, and read `SHIP`.
    """
    spec_dir = root / ".specs" / feature
    run_hint = f"run the spec-eval skill on .specs/{feature} (it writes .specs/{feature}/{REVIEW_FILE})"

    if not spec_dir.is_dir():
        return [f".specs/{feature}: folder does not exist"]

    # Stage 1 — structural pre-check.
    present: list[str] = []
    for name in SPEC_FILES:
        path = spec_dir / name
        if not path.is_file():
            continue
        if not path.read_text(encoding="utf-8", errors="replace").strip():
            return [f".specs/{feature}: {name} is empty — fill it in, then {run_hint}"]
        present.append(name)
    if not present:
        return [f".specs/{feature}: no spec files ({', '.join(SPEC_FILES)}) — write the spec, then {run_hint}"]

    # Stage 2 — require a fresh spec-eval report.
    review = spec_dir / REVIEW_FILE
    if not review.is_file():
        return [f".specs/{feature}: no spec-eval report — {run_hint}"]

    review_mtime = review.stat().st_mtime
    newest_spec = max((spec_dir / n).stat().st_mtime for n in present)
    if review_mtime < newest_spec:
        return [f".specs/{feature}: {REVIEW_FILE} is stale (spec edited after last eval) — re-{run_hint}"]

    review_text = review.read_text(encoding="utf-8", errors="replace")
    verdict_match = VERDICT_RE.search(review_text)
    if not verdict_match:
        return [f".specs/{feature}: {REVIEW_FILE} has no SHIP/BLOCK verdict — re-{run_hint}"]

    if verdict_match.group(1).upper() == "BLOCK":
        reason = "see review.md"
        for line in review_text.splitlines():
            found = REASON_RE.search(line)
            if found:
                reason = found.group(1).strip()[:240]
                break
        return [f".specs/{feature}: spec-eval verdict is BLOCK — {reason}"]

    return []


def track(payload: dict[str, Any]) -> int:
    """PostToolUse: remember every `.specs/<feature>` folder this turn touched."""
    root = repo_root(payload)
    features = touched_features(payload)
    if features:
        path = state_file(root, payload)
        existing = set(load_state(path).get("features", []))
        save_state(path, existing | features)
    return 0


def stop(payload: dict[str, Any]) -> int:
    """Stop: block the turn unless every touched feature has a fresh SHIP report."""
    root = repo_root(payload)
    features = set(load_state(state_file(root, payload)).get("features", []))
    if not features:
        return 0

    all_fails: list[str] = []
    for feature in sorted(features):
        all_fails.extend(evaluate_feature(root, feature))

    if not all_fails:
        clear_state(root, payload)
        return 0

    shown = "\n".join(f"- {item}" for item in all_fails[:20])
    extra = "" if len(all_fails) <= 20 else f"\n- ... {len(all_fails) - 20} more"
    reason = (
        "spec-eval gate blocked this turn. A .specs change is not ready to land.\n"
        "Fix each item below, then run the spec-eval skill so it writes a fresh "
        "review.md with a SHIP verdict. Do not end the turn until the gate passes.\n"
        + shown
        + extra
    )
    sys.stdout.write(json.dumps({"decision": "block", "reason": reason}) + "\n")
    return 0


def main() -> int:
    mode = sys.argv[1] if len(sys.argv) > 1 else "stop"
    payload = read_hook_input()
    if mode == "track":
        return track(payload)
    if mode in {"stop", "codex-stop", "claude-stop"}:
        return stop(payload)
    sys.stderr.write(f"unknown mode: {mode}\n")
    return 2


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        sys.stderr.write(f"spec-eval hook error: {exc}\n")
        raise
