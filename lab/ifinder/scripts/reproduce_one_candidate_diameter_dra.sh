#!/usr/bin/env bash
# reproduce_one_candidate_diameter_dra.sh — DA→VA smoke for Nextgen-DRA (diameter).
#
# Scope    : Nextgen-DRA 0.1.0-SNAPSHOT × one pattern × full discovery
# Stages   : DA (this wrapper runs the STATIC stages; EA needs a funded claude
#            account and the docker-dra testbed built — see artifacts/README.md)
# Target   : target/dra_code (symlink to the Nextgen-DRA repo)
# Guard    : aborts loudly when the agent backend reports no credit — an empty
#            result JSON must never be mistaken for "no findings".
#
# Usage    : PATTERN=PA1 bash scripts/reproduce_one_candidate_diameter_dra.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IFINDER="$(cd "$SCRIPT_DIR/.." && pwd)"
PATTERN="${PATTERN:-PA1}"
MODEL="${MODEL:-deepseek-v4-pro-0813}"
TARGET_NAME="dra"

DA_OUT="$IFINDER/outputs/discovery_results/${TARGET_NAME}/${PATTERN}.json"
RAW_OUT="$IFINDER/outputs/discovery_results/${TARGET_NAME}/${PATTERN}.raw.txt"

echo "[1/3] offline KB consistency"
python3 "$SCRIPT_DIR/check_diameter_kb.py"

echo "[2/3] DA discovery pattern=$PATTERN target=$TARGET_NAME model=$MODEL"
ifinder run --scope scope_dra.json --patterns "$PATTERN" \
    --stage discovery --target "$TARGET_NAME" --model "$MODEL"

echo "[3/3] guard: agent-backend health"
if grep -qiE "credit balance|authentication_error|not_found_error.*model" "$RAW_OUT" 2>/dev/null; then
    echo "AGENT BACKEND FAILED — raw transcript:"
    cat "$RAW_OUT"
    exit 2
fi

python3 - "$DA_OUT" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
c = d.get("coverage_report", {})
print(f"candidates={len(d.get('candidates', []))} "
      f"coverage: {c.get('audited_messages', 0)}/{c.get('total_messages', 0)} msgs, "
      f"{c.get('audited_ies', 0)}/{c.get('total_ies', 0)} IEs")
if c.get("total_messages", 0) == 0:
    print("WARNING: empty coverage — the DA likely did not really run "
          "(check the raw transcript); do not treat this as 'clean'.")
    sys.exit(3)
PY
echo "done."
