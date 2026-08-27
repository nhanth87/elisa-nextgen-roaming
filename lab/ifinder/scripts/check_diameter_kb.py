#!/usr/bin/env python3
"""check_diameter_kb.py — offline consistency validation for the diameter/ contribution.

Validates (no LLM, no network):
  1. every procedure message exists in schema/diameter/generated/message_schemas
  2. every mandatory_ies / ies entry referenced by procedures exists in the schema IE set
     or in ie_catalog
  3. every scope target's target_codebase + scan_dirs exist on disk
  4. generated normalized files carry the same message/IE sets as raw/
Exit code 0 = consistent; 1 = problems printed.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DIA = ROOT / "schema" / "diameter"


def load(p: Path):
    return json.loads(Path(p).read_text(encoding="utf-8"))


def main() -> int:
    problems: list[str] = []

    raw_msgs_dir = DIA / "raw" / "Message"
    raw_ies_dir = DIA / "raw" / "IE"
    gen = load(DIA / "generated" / "message_schemas.normalized.json")
    cat = load(DIA / "generated" / "ie_catalog.normalized.json")

    raw_msgs = {json.load(f.open())["message_name"] for f in raw_msgs_dir.glob("*.json")}
    raw_ies = {json.load(f.open())["ie_name"] for f in raw_ies_dir.glob("*.json")}

    gen_msgs = gen.get("messages", {})
    if set(gen_msgs) != raw_msgs:
        problems.append(f"generated messages != raw: only-in-raw={sorted(raw_msgs - set(gen_msgs))} "
                        f"only-in-gen={sorted(set(gen_msgs) - raw_msgs)}")
    if set(cat.get("ies", {})) != raw_ies:
        problems.append("ie_catalog != raw IE set")

    for mname, mdef in gen_msgs.items():
        for ie in mdef.get("ies", {}):
            if ie not in raw_ies:
                problems.append(f"message {mname}: IE '{ie}' not in catalog")

    proc_files = sorted((ROOT / "procedure" / "diameter").glob("*.json"))
    if not proc_files:
        problems.append("procedure/diameter is empty")
    known = set(gen_msgs)
    for fp in proc_files:
        proc = load(fp)
        for step in proc.get("message_flow", []):
            m = step.get("message")
            if m and m not in known:
                # relay hops intentionally blank; anything else must be a real message
                problems.append(f"{fp.name}: unknown message '{m}' (seq {step.get('seq')})")
            for ie in step.get("mandatory_ies", []):
                if ie not in raw_ies:
                    problems.append(f"{fp.name}: mandatory_ie '{ie}' not in catalog")

    scopes = sorted((ROOT / "scope" / "diameter").glob("*.json"))
    for sp in scopes:
        sc = load(sp)
        for t in sc.get("targets", []):
            base = ROOT / t["target_codebase"]
            if not base.exists():
                problems.append(f"{sp.name}: target_codebase missing: {t['target_codebase']}")
                continue
            for d in t.get("scan_dirs", []):
                if not (base / d).exists():
                    problems.append(f"{sp.name}: scan_dir missing: {d}")

    if problems:
        print("INCONSISTENT:")
        for p in problems:
            print(" -", p)
        return 1
    msgs = len(raw_msgs)
    ies = len(raw_ies)
    print(f"OK: diameter KB consistent — {msgs} messages, {ies} AVPs, "
          f"{len(proc_files)} procedures, {len(scopes)} scope file(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
