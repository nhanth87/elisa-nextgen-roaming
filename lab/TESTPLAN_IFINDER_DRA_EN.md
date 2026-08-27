# TEST PLAN: iFinder → DRA → sas-diameter-testapp over SCTP (Netty kernel transport)

> End-to-end security-lab procedure: **iFinder static analysis of the DRA codebase (DA→VA)**
> combined with a **live relay lab** where all Diameter legs ride on **SCTP**
> (kernel SCTP via Netty's `netty-transport-sctp` / `com.sun.nio.sctp`).
> Every command below was executed and verified green on this host (2026-08-26).
>
> JDK: Java 25 only (mise zulu-25). Do not lower `maven.compiler.release`.

---

## 0. Prerequisites & layout

```bash
cd /home/meodien/Desktop/ethiopia-working-dir/worktrees/Nextgen-DRA
export JAVA_HOME=$(mise where java@zulu-25)
```

Verify kernel SCTP is available (Netty uses the OS stack — no userspace shim):

```bash
lsmod | grep -w sctp            # module must be loaded
ls /proc/net/sctp/assocs        # control plane present
# if missing: sudo modprobe sctp
```

| Component | Path | Notes |
|---|---|---|
| DRA dist runtime | `dist/lab-run/` | symlinks into `dist/dra` + own configs |
| HSS simulator | `lab/sas-diameter-testapp/target/sas-diameter-testapp-lab.jar` | fat jar |
| iFinder clone | `lab/ifinder/` | branch `feat/diameter-dra` |
| iFinder venv | `lab/ifinder-venv/` | rebuildable |
| Gateway shim | `lab/aibox-shim.py` | reads token from `lab/ifinder/.env` |
| Credentials | `lab/ifinder/.env` | NEVER commit |

Ports (all loopback):

| Port | Process | Role |
|---|---|---|
| 3868/SCTP | DRA (`mme-acc`, SERVER) | ingress for seeder / future EA |
| 3869/SCTP | testapp (`hss-a`) | DRA dials out here (CLIENT) |
| 8080 | DRA admin REST | peers / rules |
| 8086 | testapp web | health / messages / subscriber / metrics |
| 8787 | shim → api.ai-box.vn | Anthropic-format gateway for claude CLI |

Gateway models granted to this token: `deepseek-v4-flash-0731`,
`deepseek-v4-pro-0813` *(recommended for DA/VA)*, `qwen3.8-max`,
`kimi-k2.7-code`. The default `claude-opus-4-5-*` returns **403** — always
pass `--model`.

---

## 1. Start the gateway shim

The upstream gateway intermittently serves an HTML splash page and expects
BOTH `x-api-key` and `Authorization: Bearer`. The shim fixes both and retries:

```bash
cd /home/meodien/Desktop/ethiopia-working-dir/worktrees/Nextgen-DRA
SHIM_ENV=$PWD/lab/ifinder/.env setsid python3 lab/aibox-shim.py \
    > /tmp/opencode/shim.log 2>&1 < /dev/null &
```

Verify (must return JSON, never HTML):

```bash
set -a; . lab/ifinder/.env; set +a
curl -s -m 60 http://127.0.0.1:8787/v1/messages \
  -H "authorization: Bearer $ANTHROPIC_AUTH_TOKEN" \
  -H 'content-type: application/json' \
  -d '{"model":"deepseek-v4-flash-0731","max_tokens":16,
       "messages":[{"role":"user","content":"Reply OK"}]}' | head -c 120
echo   # expect: {"content":[{"signature":""...
tail -3 /tmp/opencode/shim.log   # "[shim] POST ... -> 200 (N bytes, attempt k)"
```

---

## 2. Build artifacts (skip if already built)

```bash
mvn -q -pl bench,elisa-dra,lab/sas-diameter-testapp -am package -DskipTests
bash dist-tools/package-dist.sh          # → dist/dra/ (guards JDK25 + bytecode 69)
ls -la dist/lab-run/quarkus-run.jar      # symlink → ../dra/quarkus-run.jar
```

---

## 3. Switch the lab to SCTP

### 3a. Peer config

`dist/lab-run/configs/dra-peers.json` must carry `"transport": "SCTP"` on both
peers (a TCP copy is kept as `dra-peers-tcp.json.bak`):

```bash
sed 's/"transport": "TCP"/"transport": "SCTP"/g' \
    dist/lab-run/configs/dra-peers-tcp.json.bak > dist/lab-run/configs/dra-peers.json
grep -n transport dist/lab-run/configs/dra-peers.json   # 2 hits, both SCTP
```

> CLIENT links bound to loopback destinations are pinned to `127.0.0.1`
> automatically (`CorsacPeerFabric`) so SCTP INITs advertise exactly one
> address — wildcard binds make kernel multi-homing announce every interface
> (docker/LAN/wireguard) and stray INITs flip server-side link state.

### 3b. Simulator in SCTP mode

Launch WITHOUT `--tcp` (default transport is SCTP):

```bash
setsid "$JAVA_HOME/bin/java" -jar \
    lab/sas-diameter-testapp/target/sas-diameter-testapp-lab.jar \
    --listen-port 3869 --web-port 8086 \
    > /tmp/opencode/testapp.log 2>&1 < /dev/null &
sleep 7
curl -s http://127.0.0.1:8086/api/health
# {"status":"up","diameterListening":true,...}
grep transport /tmp/opencode/testapp.log | head -1   # must say transport=sctp
```

Seeded subscribers (the oracle table):

| IMSI (16 digits) | State | Expected ULA through DRA |
|---|---|---|
| 4520402000000001 | attached | **2001** ok |
| 4520402000000002 | barred | **2001** + OPERATOR_DETERMINED_BARRING |
| 4520402000000003 | detached | **5421** user detached |
| 4520402000000004 | vectors=0 | **2001** ok |
| anything else | unknown | **5001** user unknown |

---

## 4. Start the DRA

Run from `dist/lab-run` (bootstrap resolves `configs/dra-peers.json` via CWD):

```bash
setsid bash -c 'cd "'$PWD'/dist/lab-run" && exec ./run.sh' \
    > /tmp/opencode/dra.log 2>&1 < /dev/null &
sleep 15
curl -s http://127.0.0.1:8080/api/peers | python3 -m json.tool
# hss-a state=OPEN (channelUp+ceaOk+watchdogValid); mme-acc IDLE until §5
```

Load rules (SoT is REST; JSON files only seed):

```bash
curl -s -X PUT http://127.0.0.1:8080/api/rules \
  -H 'Content-Type: application/json' \
  -d @dist/lab-run/configs/dra-rules-lab.json
# {"applied":true,"version":1}
```

Sanity: the association must be SCTP end-to-end:

```bash
strings /tmp/opencode/dra.log | grep 'ipChannelType=SCTP' | head -2
grep 'transport=sctp' /tmp/opencode/testapp.log | head -1
```

---

## 5. Smoke E2E over SCTP: seeder → DRA → testapp

Use the dedicated SCTP client (JDK built-in `com.sun.nio.sctp`; mirrors
SeederClient's wire behaviour — CER app-id 0, ULR with Dest-Host):

```bash
java -cp bench/target/classes:elisa-dra/target/classes \
  et.elisa.dra.bench.SctpSeederClient \
  --host 127.0.0.1 --port 3868 --src-port 38680 \
  --count 4 --imsi-prefix 45204020 \
  --dest-host hss-a.epc.mnc01.mcc452.3gppnetwork.org
```

Expected:

```
sent     : 4
received : 4 (0 timeouts)
last rc  : 2001          # exit code 0 only when 4/4 AND final rc == 2001
```

Ground truth at the simulator — **exactly one req+ans pair per IMSI**:

```bash
curl -s http://127.0.0.1:8086/api/messages | python3 -c "
import json,sys
for m in json.load(sys.stdin)[-8:]:
    print(m['time'][11:23], m['direction'], m['command'],
          m.get('session','')[:30], 'rc='+str(m.get('result')), m.get('details','')[:36])"
# 2001 ok / 2001 subscriber barred / 5421 user detached / 2001 ok
```

Relay decisions on the DRA:

```bash
grep '\[relay\] decision' /tmp/opencode/dra.log | tail -4   # Forward × 4
```

---

## 6. iFinder — offline gate (no tokens burned)

```bash
cd lab/ifinder
python3 scripts/check_diameter_kb.py
# OK: diameter KB consistent — 10 messages, 24 AVPs, 5 procedures, 1 scope file(s)
readlink target/dra_code    # ../../.. → repo root; scope scans the elisa-dra module (single-app layout since 2026-08-26)

python3 -m venv ../ifinder-venv && ../ifinder-venv/bin/pip install -e src
export PATH="$PWD/../ifinder-venv/bin:$PATH"
```

---

## 7. iFinder — DA discovery (static analysis)

```bash
cd lab/ifinder
set -a; . ./.env; set +a                 # credentials
export ANTHROPIC_BASE_URL=http://127.0.0.1:8787   # ALWAYS via the shim
PATTERN=PA1 MODEL=deepseek-v4-pro-0813 \
  bash scripts/reproduce_one_candidate_diameter_dra.sh
```

Reference timing: **~13 min**, ~50 API calls (pro model). Success looks like:

```
candidates=2 coverage: 10/10 msgs, 56/56 IEs
done.
```

The wrapper fails loudly instead of faking success: exit 2 on backend
credit/auth errors, exit 3 on empty coverage — `candidates=0` counts only
when the guard passed.

Artifacts: `outputs/discovery_results/dra/<PATTERN>.json` (+ `.raw.txt`
transcript).

---

## 8. iFinder — VA vetting

VA cross-checks each candidate against prerequisite handlers: is the "missing
validation" truly absent everywhere reachable?

```bash
cd lab/ifinder
set -a; . ./.env; set +a
export ANTHROPIC_BASE_URL=http://127.0.0.1:8787
PATH="$PWD/../ifinder-venv/bin:$PATH" \
ifinder run --scope scope_dra.json --patterns PA1 --stage vetting \
  --target dra --model deepseek-v4-pro-0813
# ~10 min for 2 candidates → "VA dra/PA1: 1 feasible"

python3 -c "
import json
d=json.load(open('outputs/vetting_results/dra/PA1.json'))
print(d['statistics'])
for r in d['results']:
    print(r['candidate_id'], r['verdict'])"
```

Reference verdicts (2026-08-24 run):

| Candidate | Site | Verdict |
|---|---|---|
| DA-PA1-001 | `DiameterWireCodec.decode:96` — V-flag AVP with Length 8..11 still reads a 4-byte vendor-id → readerIndex desync → IndexOutOfBounds | **FEASIBLE** (latent; decode() is off the production ingress path but alive in bench/raw paths) |
| DA-PA1-002 | `decode:100` negative dataLength clamp | INFEASIBLE |

Recommended hardening: gate `avpLength >= AVP_HEADER_LENGTH + 4` whenever
FLAG_V is set, before reading the vendor-id.

---

## 9. (Optional) All six patterns

```bash
cd lab/ifinder
set -a; . ./.env; set +a
export ANTHROPIC_BASE_URL=http://127.0.0.1:8787
MODEL=deepseek-v4-pro-0813 bash scripts/reproduce_full_diameter_dra.sh
# ~10–15 min per pattern, summary table at the end
```

Token-saving priority: **PB2** (invalid-state — peer-state/HBH tables, very
DRA-shaped), then **PC1** (resource exhaustion), then the rest.
EA (live exploitation with Go PoCs against `testbed/docker-dra`) is out of
scope for this runbook.

---

## 10. Troubleshooting (real failures hit during bring-up)

| Symptom | Cause | Fix |
|---|---|---|
| curl shim returns `<!doctype html` | gateway flap | shim auto-retries (watch `attempt` in shim.log); restart shim if exhausted |
| `403 Token này không có quyền truy cập model claude-opus-4-5...` | default model not entitled | always pass `--model deepseek-v4-pro-0813` (or flash/qwen/kimi); wrapper honours `MODEL=` |
| `401 Token không hợp lệ` / `Credit balance is too low` | env not sourced; CLI went to real Anthropic | `set -a; . lab/ifinder/.env; set +a` first; check `$ANTHROPIC_BASE_URL` |
| testapp boot fails `An error occured while establishing a peer` | port still held by previous instance | kill old JVMs, wait for `ss` to show :3869 free, relaunch |
| DRA boot fails same error | :3868 held | same — wait a few seconds after pkill |
| seeder answers carry `rc=3010` and Origin-Host = hss-a | simulator's corsac could not ROUTE the ULA: answer lacked Vendor-Specific-Application-Id so `canSendMessage()` returned false; error answer echoes request Dest-Host as Origin-Host | use the patched testapp (answers are tagged with VS-AID); verify `grep -c 'Can not route' /tmp/opencode/testapp.log` stays 0 |
| `rc=5001` + Error-Message "mandatory bit set for 258..." when dialing the simulator directly | raw handcrafted ULR rejected by strict parser (AVP 258 M-bit unknown) | go through the DRA (it re-encodes properly) or drop AVP 258 from handcrafted frames |
| `CEA rc=3010 "invalid remote hostname in CER"` direct to :3869 | CER Origin-Host ≠ provisioned peer identity (`dra1.epc...`) | pass `-Dseeder.host=dra1.epc.mnc01.mcc452.3gppnetwork.org` for direct-to-simulator probes |
| `Received connect request from non provisioned <ip>` warnings | kernel SCTP multi-homing probes from other interfaces | cosmetic; DRA CLIENT links now bind loopback explicitly; ignore residual noise |
| seeder `received==sent` but wrong rc / instant replies | rules not loaded (DRA self-answers 3002) or candidates empty | PUT rules again; `[relay] decision` lines in dra.log must read `Forward` |
| `PeerNotReadyException` right after lab start | first traffic raced the link poll | self-heals ≤100 ms (refreshRegistryBeforeFail); just resend |
| claude CLI hangs minutes with no output | slow/flapping gateway | retry; confirm shim.log shows 200s flowing |

---

## 11. Cleanup (MANDATORY — RAM is shared across worktrees)

```bash
pkill -f quarkus-run                     # DRA
pkill -f sas-diameter-testapp-lab.jar    # simulator
pkill -f aibox-shim.py                   # gateway shim
pkill -f 'bin/ifinder'; pkill -f claude  # leftover agents
sleep 3
ss -tlnp | grep -E ':(3868|3869|8080|8086|8787)\b' || echo "ALL PORTS FREE"
ss -Slnp | grep -E ':386[89]\b' || echo "no SCTP listeners"
ps -eo pid,args | grep -E 'quarkus-run|sas-diameter|aibox-shim' | grep -v grep || echo "no strays"
```

Nothing needs to stay running between sessions — every component starts in
under 30 seconds.

---

## Session cheat-sheet

```bash
cd .../Nextgen-DRA && export JAVA_HOME=$(mise where java@zulu-25)
# 1 shim        → curl :8787 returns JSON
# 2 testapp SCTP→ :8086 health up, log says transport=sctp
# 3 DRA         → peers hss-a OPEN (ipChannelType=SCTP) → PUT rules applied v1
# 4 smoke       → SctpSeederClient 4/4, last rc=2001; testapp req+ans pairs
#                 2001 / 2001 barred / 5421 / 2001
# 5 ifinder DA  → PATTERN=PA1 MODEL=deepseek-v4-pro-0813 reproduce_...
# 6 ifinder VA  → ifinder run --stage vetting → FEASIBLE/INFEASIBLE
# 7 cleanup     → pkill everything, ports free
```

## Transport notes (why these steps look like this)

- **Kernel SCTP only**: Netty's `NioSctp*` wraps `com.sun.nio.sctp` (jdk.sctp);
  the `sctp` kernel module must be loaded. No userspace tunnel involved.
- **Single-address INIT**: DRA CLIENT binds the remote loopback address so the
  association advertises one address; wildcard binds trigger multi-homing
  noise that destabilises server-side link state in corsac.
- **Answer tagging**: mobius-corsac refuses to route vendor-specific answers
  lacking a `Vendor-Specific-Application-Id` AVP (`canSendMessage()==false`
  → DIAMETER_UNKNOWN_PEER 3010 with the request echoed back). The simulator
  now stamps `{auth=<app-id>}` onto every answer it emits.
- Watchdog interval is 30 s; first DWR/DWA exchange happens one interval after
  OPEN. Killing either side mid-window produces one clean reconnect cycle.
