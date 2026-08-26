#!/usr/bin/env python3
"""Build docs/requirements/acceptance-matrix.json from STORY files + tracker."""

from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REQ = ROOT / "docs" / "requirements"
TRACKER = REQ / "AGENT-REQUIREMENT-IMPLEMENTATION.md"
OUT = REQ / "acceptance-matrix.json"

AC_CHECKBOX_RE = re.compile(r"^\s*-\s*\[[ xX]\]\s+(.*)$")
AC_DASH_GIVEN_RE = re.compile(r"^\s*-\s+(.*\bGiven\b.*)$", re.I)
AC_NUMBERED_RE = re.compile(r"^\s*\d+\.\s+\*\*AC-\d+\*\*:\s+(.*)$")
AC_NUMBERED_LOOSE_RE = re.compile(r"^\s*\d+\.\s+(.*)$")
AC_TABLE_RE = re.compile(r"^\|\s*(AC-\d+)\s*\|\s*(.+?)\s*\|$")
TRACKER_ROW_RE = re.compile(
    r"\|\s*(EPIC-\d+)\s*\|\s*\[?(STORY-\d+)\]?[^|]*\|\s*[^|]*\|\s*([a-z_-]+)\s*\|",
    re.I,
)


def story_id(path: Path) -> str:
    m = re.search(r"(EPIC-\d+).*(STORY-\d+)", str(path))
    if not m:
        return path.stem
    return f"{m.group(1)}/{m.group(2)}"


def extract_acs(text: str) -> list[str]:
    in_ac = False
    acs: list[str] = []
    for line in text.splitlines():
        if re.match(r"^##+\s+Acceptance Criteria", line, re.I):
            in_ac = True
            continue
        if in_ac and re.match(r"^##+\s+", line):
            break
        if not in_ac:
            continue
        stripped = re.sub(r"\s+", " ", line).strip()
        if not stripped:
            continue
        m = AC_CHECKBOX_RE.match(line)
        if m:
            acs.append(re.sub(r"\s+", " ", m.group(1)).strip())
            continue
        m = AC_TABLE_RE.match(stripped)
        if m and not set(m.group(2)) <= {"-", " "}:
            acs.append(f"{m.group(1)}: {re.sub(r'\s+', ' ', m.group(2)).strip()}")
            continue
        m = AC_NUMBERED_RE.match(line)
        if m:
            acs.append(re.sub(r"\s+", " ", m.group(1)).strip())
            continue
        m = AC_DASH_GIVEN_RE.match(line)
        if m:
            acs.append(re.sub(r"\s+", " ", m.group(1)).strip())
            continue
        m = AC_NUMBERED_LOOSE_RE.match(line)
        if m:
            body = re.sub(r"\s+", " ", m.group(1)).strip()
            if body and not body.startswith("|"):
                acs.append(body)
    return acs


def tracker_status() -> dict[str, str]:
    status: dict[str, str] = {}
    if not TRACKER.exists():
        return status
    for line in TRACKER.read_text(encoding="utf-8").splitlines():
        m = TRACKER_ROW_RE.search(line)
        if m:
            status[f"{m.group(1)}/{m.group(2)}"] = m.group(3).lower()
    return status


def main() -> None:
    statuses = tracker_status()
    stories = []
    for path in sorted(REQ.glob("EPIC-*/STORY-*.md")):
        sid = story_id(path)
        acs = extract_acs(path.read_text(encoding="utf-8"))
        stories.append(
            {
                "id": sid,
                "path": str(path.relative_to(ROOT)),
                "acceptance_criteria": acs,
                "ac_count": len(acs),
                "tracker_status": statuses.get(sid, "unknown"),
                "verified": False,
            }
        )
    missing_acs = [s["id"] for s in stories if s["ac_count"] == 0]
    unknown = [s["id"] for s in stories if s["tracker_status"] == "unknown"]
    payload = {
        "version": 2,
        "generated_from": "docs/requirements/**/STORY-*.md + AGENT-REQUIREMENT-IMPLEMENTATION.md",
        "story_count": len(stories),
        "ac_count": sum(s["ac_count"] for s in stories),
        "stories_missing_acs": missing_acs,
        "stories_unknown_tracker": unknown,
        "stories": stories,
    }
    OUT.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print(
        f"Wrote {OUT} stories={payload['story_count']} acs={payload['ac_count']}"
        f" missing_acs={len(missing_acs)} unknown_tracker={len(unknown)}"
    )


if __name__ == "__main__":
    main()
