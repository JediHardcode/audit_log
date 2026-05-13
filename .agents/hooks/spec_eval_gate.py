#!/usr/bin/env python3
"""Shared spec-eval lifecycle hook for Codex, Claude Code, and compatible agents."""

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
SKILL_REL_PATH = Path(".claude/.skills/spec-eval")


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


def report_dir(root: Path, payload: dict[str, Any]) -> Path:
    return state_file(root, payload).with_suffix("")


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
    for target in [state_file(root, payload), report_dir(root, payload)]:
        if target.is_file():
            target.unlink(missing_ok=True)
        elif target.is_dir():
            for child in target.glob("*"):
                if child.is_file():
                    child.unlink(missing_ok=True)
            target.rmdir()


def read_file(path: Path) -> tuple[str, list[str]]:
    if not path.exists():
        return "", []
    text = path.read_text(encoding="utf-8", errors="replace")
    return text, text.splitlines()


def first_line(lines: list[str], pattern: str) -> str | None:
    regex = re.compile(pattern, re.IGNORECASE)
    for idx, line in enumerate(lines, start=1):
        if regex.search(line):
            return f"L{idx}: {line.strip()[:160]}"
    return None


def has_any(text: str, patterns: list[str]) -> bool:
    return any(re.search(pattern, text, re.IGNORECASE | re.MULTILINE) for pattern in patterns)


def add_result(rows: list[tuple[str, str, str]], fails: list[str], status: str, axis: str, detail: str) -> None:
    rows.append((status, axis, detail))
    if status == "FAIL":
        fails.append(f"[FAIL] {axis}: {detail}")


def evaluate_feature(root: Path, feature: str) -> tuple[str, list[str]]:
    skill_dir = root / SKILL_REL_PATH
    repo_rubric = root / ".specs/_eval-checklist.md"
    skill_ref_rubric = skill_dir / "references/_eval-checklist.md"
    spec_dir = root / ".specs" / feature

    req_text, req_lines = read_file(spec_dir / "requirements.md")
    design_text, design_lines = read_file(spec_dir / "design.md")
    tasks_text, tasks_lines = read_file(spec_dir / "tasks.md")

    rows: list[tuple[str, str, str]] = []
    fails: list[str] = []

    if not (skill_dir / "SKILL.md").exists():
        add_result(rows, fails, "FAIL", "Skill setup", f"missing {SKILL_REL_PATH}/SKILL.md")
    if not repo_rubric.exists() and not skill_ref_rubric.exists():
        add_result(rows, fails, "FAIL", "Skill setup", "missing spec-eval rubric")

    ac_heading = first_line(req_lines, r"acceptance criteria|criteria|акцепт|при[её]м")
    testable_cue = first_line(
        req_lines,
        r"\b(WHEN|IF|WHILE|GIVEN|THEN|MUST|SHALL)\b|returns?|status|HTTP|200|400|only|sorted|cursor|limit|error",
    )
    if not req_text:
        add_result(rows, fails, "FAIL", "Testability", "missing requirements.md")
    elif not ac_heading:
        add_result(rows, fails, "FAIL", "Testability", "requirements.md has no acceptance-criteria section")
    elif not testable_cue:
        add_result(rows, fails, "FAIL", "Testability", "acceptance criteria lack binary pass/fail cues")
    else:
        add_result(rows, fails, "PASS", "Testability", f"{ac_heading}; {testable_cue}")

    missing_design = []
    if not has_any(design_text, [r"sort|order|timestamp"]):
        missing_design.append("sort order")
    if not has_any(design_text, [r"tie[- ]?break|tiebreak|secondary|id\b"]):
        missing_design.append("tiebreaker")
    if not has_any(design_text, [r"cursor|pagination|page size|limit"]):
        missing_design.append("pagination contract")
    if not has_any(design_text, [r"status|HTTP|error response|problem\+json|4\d\d|5\d\d"]):
        missing_design.append("error/status contract")
    if not has_any(design_text, [r"index|indexes|индекс"]):
        missing_design.append("index strategy")
    if not design_text:
        add_result(rows, fails, "FAIL", "Determinism", "missing design.md")
    elif missing_design:
        add_result(rows, fails, "FAIL", "Determinism", "missing " + ", ".join(missing_design))
    else:
        evidence = first_line(design_lines, r"sort|order|cursor|index|status|HTTP") or "contract cues present"
        add_result(rows, fails, "PASS", "Determinism", evidence)

    missing_tasks = []
    if not has_any(tasks_text, [r"^- \[ \]|^\d+\.|^##?\s+", r"task", r"задач"]):
        missing_tasks.append("task list")
    if not has_any(tasks_text, [r"requirements?\.md|design\.md|AC[- ]?\d+|REQ[- ]?\d+|R\d+|§|section"]):
        missing_tasks.append("requirement/design references")
    if not has_any(tasks_text, [r"Definition of Done|DoD|done when|acceptance|check|verify|test"]):
        missing_tasks.append("definition of done")
    if not has_any(tasks_text, [r"depends|dependency|after|blocked by|requires|завис"]):
        missing_tasks.append("dependencies")
    if not tasks_text:
        add_result(rows, fails, "FAIL", "Decomposition", "missing tasks.md")
    elif missing_tasks:
        add_result(rows, fails, "FAIL", "Decomposition", "missing " + ", ".join(missing_tasks))
    else:
        evidence = first_line(tasks_lines, r"depends|DoD|Definition of Done|requirements?\.md|design\.md") or "task cues present"
        add_result(rows, fails, "PASS", "Decomposition", evidence)

    rubric = repo_rubric if repo_rubric.exists() else skill_ref_rubric
    report_lines = [
        f"# spec-eval: .specs/{feature}",
        "",
        f"Skill: `{SKILL_REL_PATH}/SKILL.md`",
        f"Rubric: `{rubric.relative_to(root)}`",
        "",
        "| Result | Axis | Evidence / gap |",
        "|--------|------|----------------|",
    ]
    for status, axis, detail in rows:
        report_lines.append(f"| [{status}] | {axis} | {detail.replace('|', '/')} |")
    report_lines.extend(["", "Verdict: " + ("BLOCK" if fails else "SHIP")])
    return "\n".join(report_lines) + "\n", fails


def track(payload: dict[str, Any]) -> int:
    root = repo_root(payload)
    features = touched_features(payload)
    if features:
        path = state_file(root, payload)
        existing = set(load_state(path).get("features", []))
        save_state(path, existing | features)
    return 0


def stop(payload: dict[str, Any]) -> int:
    root = repo_root(payload)
    path = state_file(root, payload)
    features = set(load_state(path).get("features", []))
    if not features:
        return 0

    reports = report_dir(root, payload)
    reports.mkdir(parents=True, exist_ok=True)
    all_fails: list[str] = []
    report_paths: list[Path] = []
    for feature in sorted(features):
        report, fails = evaluate_feature(root, feature)
        report_path = reports / f"{feature}.md"
        report_path.write_text(report, encoding="utf-8")
        report_paths.append(report_path)
        all_fails.extend(f".specs/{feature}: {fail}" for fail in fails)

    if not all_fails:
        clear_state(root, payload)
        return 0

    shown = "\n".join(f"- {item}" for item in all_fails[:20])
    extra = "" if len(all_fails) <= 20 else f"\n- ... {len(all_fails) - 20} more"
    report_note = "\nReports:\n" + "\n".join(f"- {path}" for path in report_paths)
    reason = "spec-eval skill found [FAIL] items. Fix the spec before ending this turn.\n" + shown + extra + report_note
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
