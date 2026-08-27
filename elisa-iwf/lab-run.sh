#!/usr/bin/env bash
set -euo pipefail

LAB_ROOT="$(cd "$(dirname "$0")" && pwd)"
HSS_DIR="/home/meodien/Desktop/ethiopia-working-dir/worktrees/Nextgen-DRA/lab/sas-diameter-testapp"
HSS_JAR="$HSS_DIR/target/sas-diameter-testapp-lab.jar"
DRA_DIST="/home/meodien/Desktop/ethiopia-working-dir/worktrees/Nextgen-DRA/dist/lab-run"
DRA_JAR="$DRA_DIST/quarkus-run.jar"
LOG_DIR="$LAB_ROOT/lab-run/logs"
RESULTS_FILE="$LAB_ROOT/lab-run/results.json"

# Port allocation
HSS_PORT=3869
DRA_IWF_PORT=3870
TEST_CLIENT_PORT=38691

# PIDs
HSS_PID=""
DRA_PID=""

mkdir -p "$LOG_DIR"

cleanup() {
    echo "[orchestrator] Cleaning up..."
    [[ -n "$HSS_PID" ]] && kill "$HSS_PID" 2>/dev/null || true
    [[ -n "$DRA_PID" ]] && kill "$DRA_PID" 2>/dev/null || true
    sleep 1
    echo "[orchestrator] Done."
}
trap cleanup EXIT

kill_stale() {
    echo "[orchestrator] Killing stale processes..."
    # Kill only our specific test processes, not DRA that might be shared
    pkill -f "sas-diameter-testapp-lab.jar" 2>/dev/null || true
    sleep 1
}

start_hss() {
    echo "[orchestrator] Starting HSS testapp on port $HSS_PORT..."
    export JAVA_HOME=$(mise where java@zulu-25)
    java -jar "$HSS_JAR" \
        --listen-port "$HSS_PORT" \
        --bind 127.0.0.1 \
        --origin-host hss.epc.mnc01.mcc452.3gppnetwork.org \
        --origin-realm epc.mnc01.mcc452.3gppnetwork.org \
        --peer-host dra1.epc.mnc01.mcc452.3gppnetwork.org \
        --peer-realm epc.mnc01.mcc452.3gppnetwork.org \
        > "$LOG_DIR/hss-testapp.log" 2>&1 &
    HSS_PID=$!
    echo "[orchestrator] HSS PID=$HSS_PID"
    sleep 3
    if ! kill -0 "$HSS_PID" 2>/dev/null; then
        echo "[orchestrator] FATAL: HSS testapp failed to start"
        cat "$LOG_DIR/hss-testapp.log"
        exit 1
    fi
    echo "[orchestrator] HSS testapp started OK"
}

check_dra() {
    echo "[orchestrator] Checking DRA is running..."
    # DRA is typically started separately; check if it's up
    if ss -lnp 2>/dev/null | grep -q ":$DRA_IWF_PORT"; then
        echo "[orchestrator] DRA is listening on port $DRA_IWF_PORT"
    else
        echo "[orchestrator] FATAL: DRA not listening on port $DRA_IWF_PORT"
        echo "[orchestrator] Start DRA first: cd $DRA_DIST && java -jar quarkus-run.jar"
        exit 1
    fi
}

run_test_round() {
    local round=$1
    local description=$2
    local imsi=$3
    local command=$4
    local expected_code=$5

    echo "[Round $round] $description"

    export JAVA_HOME=$(mise where java@zulu-25)
    java -cp "target/test-classes:target/classes:$(cat /tmp/iwf-cp.txt)" \
        et.elisa.iwf.lab.LabTestRunner "$round" "$imsi" "$command" "$expected_code" \
        > "$LOG_DIR/round-$round.log" 2>&1

    local rc=$?
    if [ $rc -eq 0 ]; then
        echo "  PASS"
    else
        echo "  FAIL (exit=$rc)"
        tail -5 "$LOG_DIR/round-$round.log"
    fi
    return $rc
}

# --- Main ---
echo "=========================================="
echo "  IWF Lab Integration Test (10 Rounds)"
echo "=========================================="
echo ""

kill_stale
start_hss
check_dra

echo ""
echo "[orchestrator] Running 10 test rounds..."
echo ""

PASS=0
FAIL=0

for round in $(seq 1 10); do
    case $round in
        1) run_test_round 1 "ULR Happy Path"       "4520402001" "ULR"   "2001" && PASS=$((PASS+1)) || FAIL=$((FAIL+1)) ;;
        2) run_test_round 2 "AIR Happy Path"       "4520402002" "AIR"   "2001" && PASS=$((PASS+1)) || FAIL=$((FAIL+1)) ;;
        3) run_test_round 3 "PUR Happy Path"       "4520402003" "PUR"   "2001" && PASS=$((PASS+1)) || FAIL=$((FAIL+1)) ;;
        4) run_test_round 4 "ULR 2nd Subscriber"   "4520402004" "ULR"   "2001" && PASS=$((PASS+1)) || FAIL=$((FAIL+1)) ;;
        5) run_test_round 5 "AIR 2nd Subscriber"   "4520402005" "AIR"   "2001" && PASS=$((PASS+1)) || FAIL=$((FAIL+1)) ;;
        6) run_test_round 6 "MIXED ULR+AIR+PUR"    "4520402006" "MIXED" "2001" && PASS=$((PASS+1)) || FAIL=$((FAIL+1)) ;;
        7) run_test_round 7 "ULR 3rd Subscriber"   "4520402007" "ULR"   "2001" && PASS=$((PASS+1)) || FAIL=$((FAIL+1)) ;;
        8) run_test_round 8 "AIR 3rd Subscriber"   "4520402008" "AIR"   "2001" && PASS=$((PASS+1)) || FAIL=$((FAIL+1)) ;;
        9) run_test_round 9 "PUR 4th Subscriber"   "4520402009" "PUR"   "2001" && PASS=$((PASS+1)) || FAIL=$((FAIL+1)) ;;
        10) run_test_round 10 "STRESS 10x ULR"     "4520402010" "STRESS" "2001" && PASS=$((PASS+1)) || FAIL=$((FAIL+1)) ;;
    esac
done

echo ""
echo "=========================================="
echo "  Results: $PASS PASS / $FAIL FAIL / 10 TOTAL"
echo "=========================================="

cat > "$RESULTS_FILE" <<EOF
{
  "timestamp": "$(date -Iseconds)",
  "total": 10,
  "pass": $PASS,
  "fail": $FAIL,
  "rounds": [
    {"round": 1,  "test": "ULR Happy Path",      "status": "$([ $PASS -ge 1 ] && echo pass || echo fail)"},
    {"round": 2,  "test": "AIR Happy Path",      "status": "$([ $PASS -ge 2 ] && echo pass || echo fail)"},
    {"round": 3,  "test": "PUR Happy Path",      "status": "$([ $PASS -ge 3 ] && echo pass || echo fail)"},
    {"round": 4,  "test": "ULR 2nd Subscriber",  "status": "$([ $PASS -ge 4 ] && echo pass || echo fail)"},
    {"round": 5,  "test": "AIR 2nd Subscriber",  "status": "$([ $PASS -ge 5 ] && echo pass || echo fail)"},
    {"round": 6,  "test": "MIXED ULR+AIR+PUR",   "status": "$([ $PASS -ge 6 ] && echo pass || echo fail)"},
    {"round": 7,  "test": "ULR 3rd Subscriber",  "status": "$([ $PASS -ge 7 ] && echo pass || echo fail)"},
    {"round": 8,  "test": "AIR 3rd Subscriber",  "status": "$([ $PASS -ge 8 ] && echo pass || echo fail)"},
    {"round": 9,  "test": "PUR 4th Subscriber",  "status": "$([ $PASS -ge 9 ] && echo pass || echo fail)"},
    {"round": 10, "test": "STRESS 10x ULR",      "status": "$([ $PASS -ge 10 ] && echo pass || echo fail)"}
  ]
}
EOF

if [ $FAIL -gt 0 ]; then
    echo "[orchestrator] Some tests failed. Check $LOG_DIR/ for details."
    exit 1
fi

echo "[orchestrator] All tests passed."
exit 0
