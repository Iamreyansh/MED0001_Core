#!/usr/bin/env python3
"""Structural launch-scope gate for the acceptance matrix and Bruno contracts."""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MATRIX = ROOT / "docs/requirements/acceptance-matrix.json"
LAUNCH_PREFIXES = (
    "EPIC-001/",
    "EPIC-002/",
    "EPIC-003/",
    "EPIC-004/",
    "EPIC-006/",
    "EPIC-007/",
    "EPIC-008/",
    "EPIC-010/",
    "EPIC-011/",
    "EPIC-012/",
    "EPIC-017/",
)
REQUIRED_BRUNO = [
    ROOT / "bruno/payments/initiate.bru",
    ROOT / "bruno/auth/customer/send-otp.bru",
]
REQUIRED_DECISIONS = {
    "EPIC-003/STORY-004": "D8",
    "EPIC-004/STORY-003": "D6",
    "EPIC-010/STORY-005": "D10",
    "EPIC-012/STORY-003": "D6",
    "EPIC-002/STORY-005": "D15",
    "EPIC-013/STORY-005": "D15",
}
FORBIDDEN_TCS_5L = re.compile(r"5,?00,?000|₹5L|Rs 5L", re.I)


def main() -> int:
    errors: list[str] = []
    for path in REQUIRED_BRUNO:
        if not path.is_file():
            errors.append(f"missing Bruno contract: {path.relative_to(ROOT)}")
    if not MATRIX.is_file():
        errors.append("missing acceptance-matrix.json")
        print_errors(errors)
        return 1
    data = json.loads(MATRIX.read_text())
    stories = data.get("stories") or []
    if not stories:
        errors.append("acceptance-matrix.json has no stories")
    by_id = {str(s.get("id")): s for s in stories}
    for sid, decision in REQUIRED_DECISIONS.items():
        story = by_id.get(sid)
        if story is None:
            errors.append(f"missing required decision story {sid}")
            continue
        if story.get("decision") != decision:
            errors.append(f"{sid} must carry decision={decision}")
        text = " ".join(story.get("acceptance_criteria") or [])
        if decision == "D6" and FORBIDDEN_TCS_5L.search(text) and "TDS 194-O" not in text:
            errors.append(f"{sid} still ties TCS to ₹5L threshold (conflicts D6)")
        if decision == "D15" and "POST /customers/me/referral/apply" in text and "no post-signup" not in text.lower():
            if "only during OTP signup" not in text and "OTP signup (D15)" not in text:
                errors.append(f"{sid} still describes post-signup referral apply (conflicts D15)")
    launch = [s for s in stories if str(s.get("id", "")).startswith(LAUNCH_PREFIXES)]
    if not launch:
        errors.append("no launch-scope stories found in matrix")
    for story in launch:
        sid = story.get("id", "?")
        status = story.get("tracker_status")
        if status == "production-ready":
            errors.append(f"{sid} is production-ready before promotion evidence")
        if status not in {
            "staging-deployed",
            "in_progress",
            "blocked",
            "pending",
        }:
            errors.append(f"{sid} has unexpected tracker_status={status}")
        text = " ".join(story.get("acceptance_criteria") or []).lower()
        if "auto-activate the pharmacy" in text and "never auto-activate" not in text and "never activates" not in text:
            errors.append(f"{sid} still describes D8-forbidden auto-activation")
    unverified = sum(1 for s in launch if story_unverified(s))
    print(
        f"acceptance-ac-gate: {len(launch)} launch stories, "
        f"{unverified} unverified (allowed until staging proofs)"
    )
    if errors:
        print_errors(errors)
        return 1
    return 0


def story_unverified(story: dict) -> bool:
    return story.get("verified") is False


def print_errors(errors: list[str]) -> None:
    print("acceptance-ac-gate FAILED:", file=sys.stderr)
    for err in errors:
        print(f"  - {err}", file=sys.stderr)


if __name__ == "__main__":
    raise SystemExit(main())
