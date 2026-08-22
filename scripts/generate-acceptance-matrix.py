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

AC_RE = re.compile(r"^\s*-\s*\[[ xX]\]\s+(.*)$")
GIVEN_RE = re.compile(r"\*\*Given\*\*|\bGiven\b", re.I)


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
        if in_ac:
            m = AC_RE.match(line)
            if m:
                acs.append(re.sub(r"\s+", " ", m.group(1)).strip())
            elif GIVEN_RE.search(line) and line.strip().startswith("-"):
                acs.append(re.sub(r"\s+", " ", line.lstrip("- ").strip()))
    return acs


def tracker_status() -> dict[str, str]:
    status: dict[str, str] = {}
    row = re.compile(
        r"\|\s*(EPIC-\d+)\s*\|\s*(STORY-\d+)\s*\|[^|]*\|\s*([a-z_]+)\s*\|",
        re.I,
    )
    if not TRACKER.exists():
        return status
    for line in TRACKER.read_text(encoding="utf-8").splitlines():
        m = row.search(line)
        if m:
            status[f"{m.group(1)}/{m.group(2)}"] = m.group(3).lower()
    return status


def main() -> None:
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
                "tracker_status": tracker_status().get(sid, "unknown"),
                "verified": False,
            }
        )
    payload = {
        "version": 1,
        "generated_from": "docs/requirements/**/STORY-*.md",
        "story_count": len(stories),
        "ac_count": sum(s["ac_count"] for s in stories),
        "stories": stories,
    }
    OUT.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {OUT} stories={payload['story_count']} acs={payload['ac_count']}")


if __name__ == "__main__":
    main()
