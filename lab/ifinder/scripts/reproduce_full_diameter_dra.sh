#!/usr/bin/env bash
# reproduce_full_diameter_dra.sh — all six patterns × Nextgen-DRA (static stages DA only),
# with per-pattern candidate/coverage table. EA live exploitation additionally needs
# testbed/docker-dra built and a funded claude account.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PATTERNS="${PATTERNS:-PA1 PA2 PB1 PB2 PB3 PC1}"
"$HERE/check_diameter_kb.py"
for p in $PATTERNS; do
    echo "== $p =="
    rc=0
    PATTERN="$p" "$HERE/reproduce_one_candidate_diameter_dra.sh" || rc=$?
    [ $rc -eq 3 ] && echo "(empty coverage — treated as not-run)" || true
done
python3 - <<'PY'
import json
from pathlib import Path
base = Path.cwd() / "outputs/discovery_results/dra"
if not base.exists():
    base = Path(__file__).resolve().parents[1] / "outputs/discovery_results/dra"
print(f"{'pattern':8} {'candidates':>10} {'audited':>12}")
for f in sorted(base.glob("*.json")):
    d = json.load(open(f))
    c = d.get("coverage_report", {})
    print(f"{d['pattern_id']:8} {len(d.get('candidates', [])):>10} "
          f"{c.get('audited_messages', 0):>6}/{c.get('total_messages', 0):<5}")
print("NOTE: candidates=0 counts as a real negative ONLY when the backend")
print("health guard passed and coverage is non-empty.")
PY
