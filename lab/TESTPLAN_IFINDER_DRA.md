# TESTPLAN: iFinder → DRA → sas-diameter-testapp (lab tự chạy)

> Bản **SCTP** (Netty kernel transport) tiếng Anh: `TESTPLAN_IFINDER_DRA_EN.md`.
> Bản này giữ phương án TCP đã verify 24/08; quy trình iFinder (mục 6–9) giống hệt.
> Quy trình end-to-end để test pipeline bảo mật: **iFinder quét code DRA (DA→VA)**
> kết hợp **lab thật** (DRA relay ↔ HSS simulator) để chứng minh hành vi.
> Mọi lệnh dưới đây đã được chạy xanh ngày 2026-08-24 trên máy này.
>
> JDK: chỉ Java 25 (mise zulu-25). Không đổi `maven.compiler.release`.

---

## 0. Điều kiện trước & cấu trúc thư mục

```bash
cd /home/meodien/Desktop/ethiopia-working-dir/worktrees/Nextgen-DRA
export JAVA_HOME=$(mise where java@zulu-25)
```

| Thành phần | Đường dẫn | Ghi chú |
|---|---|---|
| DRA dist runtime | `dist/lab-run/` | symlink sang `dist/dra` + configs riêng |
| HSS simulator | `lab/sas-diameter-testapp/target/sas-diameter-testapp-lab.jar` | fat-jar |
| iFinder clone | `lab/ifinder/` | branch `feat/diameter-dra` |
| iFinder venv | `lab/ifinder-venv/` | rebuildable |
| Gateway shim | `lab/aibox-shim.py` | đọc token từ `lab/ifinder/.env` |
| Credentials | `lab/ifinder/.env` | KHÔNG commit |

Cổng lab (đều loopback):

| Port | Tiến trình | Vai trò |
|---|---|---|
| 3868 | DRA (`mme-acc`, SERVER) | ingress cho seeder/iFinder-EA sau này |
| 3869 | testapp (`hss-a`) | DRA dial-out vào đây (CLIENT) |
| 8080 | DRA admin REST | peers / rules / telemetry |
| 8086 | testapp web | health / messages / subscriber / metrics |
| 8787 | shim → api.ai-box.vn | gateway Anthropic-format cho claude CLI |

Model được cấp trên gateway (GLM **không** có quyền): `deepseek-v4-flash-0731`,
`deepseek-v4-pro-0813` *(khuyến nghị cho DA/VA)*, `qwen3.8-max`, `kimi-k2.7-code`.
Default `claude-opus-4-5-*` sẽ bị **403** — luôn chạy với `--model`.

---

## 1. Khởi động gateway shim

Gateway ai-box thỉnh thoảng "flap" trả HTML homepage và yêu cầu CẢ HAI header
`x-api-key` lẫn `Authorization: Bearer`. Shim giải quyết cả hai + retry.

```bash
cd /home/meodien/Desktop/ethiopia-working-dir/worktrees/Nextgen-DRA
SHIM_ENV=$PWD/lab/ifinder/.env setsid python3 lab/aibox-shim.py \
    > /tmp/opencode/shim.log 2>&1 < /dev/null &
```

Kiểm tra (phải trả JSON, không phải HTML):

```bash
set -a; . lab/ifinder/.env; set +a
curl -s -m 60 http://127.0.0.1:8787/v1/messages \
  -H "authorization: Bearer $ANTHROPIC_AUTH_TOKEN" \
  -H 'content-type: application/json' \
  -d '{"model":"deepseek-v4-flash-0731","max_tokens":16,
       "messages":[{"role":"user","content":"Reply OK"}]}' | head -c 120
echo   # mong đợi: {"content":[{"signature":""...
tail -3 /tmp/opencode/shim.log   # thấy "[shim] POST ... -> 200 (N bytes, attempt k)"
```

> Nếu body là `<!doctype html>`: shim đang retry hộ (xem shim.log tăng attempt).
> Nếu vẫn HTML sau ~20s: gateway đang bảo trì — chờ rồi thử lại (shim giữ sống).

---

## 2. Build artifact (bỏ qua nếu đã build)

```bash
# Toàn bộ module + testapp jar (JDK25 bắt buộc)
mvn -q -pl bench,elisa-dra,lab/sas-diameter-testapp -am package -DskipTests

# Dist DRA (guard JDK25 + bytecode major 69; không clobber configs operator)
bash dist-tools/package-dist.sh          # → dist/dra/
```

Verify nhanh:

```bash
ls -la lab/sas-diameter-testapp/target/sas-diameter-testapp-lab.jar
ls -la dist/lab-run/quarkus-run.jar      # symlink → ../dra/quarkus-run.jar
```

---

## 3. Chạy simulator (HSS giả `hss-a`)

```bash
setsid "$JAVA_HOME/bin/java" -jar \
    lab/sas-diameter-testapp/target/sas-diameter-testapp-lab.jar \
    --tcp --listen-port 3869 --web-port 8086 \
    --status-file /tmp/opencode/testapp-status.json \
    > /tmp/opencode/testapp.log 2>&1 < /dev/null &
```

Verify:

```bash
curl -s http://127.0.0.1:8086/api/health
# mong đợi: {"status":"up","diameterListening":true,"lastMessageAgeMillis":-1}
curl -s http://127.0.0.1:8086/api/subscriber | python3 -m json.tool | grep imsi
# 5 seed: 452040200000000{1..4} + 655010000000001
```

Seed profile quan trọng cho oracle (xem §7):

| IMSI (16 số) | Trạng thái | Kỳ vọng ULA qua DRA |
|---|---|---|
| 4520402000000001 | attached | **2001** + Subscription-Data |
| 4520402000000002 | barred | **2001** + OPERATOR_DETERMINED_BARRING |
| 4520402000000003 | detached | **5421** user detached |
| 4520402000000004 | vectors=0 | **2001** ok |
| khác (vd ...005+) | không tồn tại | **5001** user unknown |

---

## 4. Chạy DRA

Chạy từ đúng thư mục `dist/lab-run` (bootstrap đọc `configs/dra-peers.json`
theo CWD):

```bash
setsid bash -c 'cd "'$PWD'/dist/lab-run" && exec ./run.sh' \
    > /tmp/opencode/dra.log 2>&1 < /dev/null &
sleep 15
```

Verify peer truth (**READY = channelUp + ceaOk + watchdogValid**, LISTEN ≠ ready):

```bash
curl -s http://127.0.0.1:8080/api/peers | python3 -m json.tool
# mong đợi: hss-a state=OPEN (3 true); mme-acc IDLE là ĐÚNG (chưa ai nối vào :3868)
```

Nạp rules (SoT là REST; file chỉ seed):

```bash
curl -s -X PUT http://127.0.0.1:8080/api/rules \
  -H 'Content-Type: application/json' \
  -d @dist/lab-run/configs/dra-rules-lab.json
# mong đợi: {"applied":true,"version":1}

curl -s http://127.0.0.1:8080/api/rules | python3 -c "
import json,sys; d=json.load(sys.stdin)
print('rules:', [r['name'] for r in d['rules']])"
# ['s6a-mvno-hss', 'gx-pcrf-binding', 'default-drop-unknown']
```

---

## 5. Smoke E2E: seeder ULR → DRA → testapp

Seeder phải (a) bind nguồn **38680** (corsac association `mme-acc` pin
remote `127.0.0.1:38680`), (b) gửi **Destination-Host** (testapp cần để build
ULA), (c) dùng prefix IMSI **16 số** khớp seed:

```bash
cd /home/meodien/Desktop/ethiopia-working-dir/worktrees/Nextgen-DRA
M2=~/.m2/repository/com/fasterxml/jackson/core
CP="bench/target/classes:elisa-dra/target/classes"
CP="$CP:$M2/jackson-databind/2.17.2/jackson-databind-2.17.2.jar"
CP="$CP:$M2/jackson-core/2.17.2/jackson-core-2.17.2.jar"
CP="$CP:$M2/jackson-annotations/2.17.2/jackson-annotations-2.17.2.jar"

java -cp "$CP" et.elisa.dra.bench.BenchScenario \
  --host 127.0.0.1 --port 3868 --src-port 38680 --connections 1 \
  --tps 1 --duration-s 4 --imsi-prefix 45204020 \
  --dest-host hss-a.epc.mnc01.mcc452.3gppnetwork.org
```

Mong đợi (4 ULR đầu = 4 profile seed):

```
sent        : 4
received    : 4 (0.000% loss)
timeouts    : 0
p50/p90/p99 : ~200 ms (qua relay + HSS sim thật)
```

Đối chiếu ground-truth ở testapp — **req+ans phải đúng 1 cặp/IMSI**:

```bash
curl -s http://127.0.0.1:8086/api/messages | python3 -c "
import json,sys
for m in json.load(sys.stdin)[-8:]:
    print(m['time'][11:23], m['direction'], m['command'],
          m.get('session','')[:30], 'rc='+str(m.get('result')), m.get('details','')[:36])"
# rc=2001 ok / rc=2001 subscriber barred / rc=5421 user detached / rc=2001 ok
```

Xem DRA quyết định route gì (log DEBUG):

```bash
grep '\[relay\] decision' /tmp/opencode/dra.log | tail -4
# [relay] decision Forward for hbh=...
```

---

## 6. iFinder — offline gate (không tốn token)

```bash
cd lab/ifinder
python3 scripts/check_diameter_kb.py
# mong đợi: OK: diameter KB consistent — 10 messages, 24 AVPs, 5 procedures, 1 scope file(s)
readlink target/dra_code    # ../../.. → trỏ về gốc Nextgen-DRA (scope scan module `elisa-dra` — layout 1 module từ 2026-08-26)
```

Venv nếu chưa có:

```bash
python3 -m venv ../ifinder-venv
../ifinder-venv/bin/pip install -e src
export PATH="$PWD/../ifinder-venv/bin:$PATH"
which ifinder && ifinder run --help | head -3
```

---

## 7. iFinder — DA discovery (static analysis, tốn token)

DA quét toàn bộ source Java của DRA theo 1 pattern, xuất candidates + coverage.

```bash
cd lab/ifinder
set -a; . ./.env; set +a                 # credentials
export ANTHROPIC_BASE_URL=http://127.0.0.1:8787   # QUA SHIM, không gọi thẳng gateway
PATTERN=PA1 MODEL=deepseek-v4-pro-0813 \
  bash scripts/reproduce_one_candidate_diameter_dra.sh
```

Thời gian tham chiếu: **~13 phút** (~50 API calls, model pro).

Kết quả thành công:

```
[3/3] guard: agent-backend health
candidates=2 coverage: 10/10 msgs, 56/56 IEs
done.
```

Guard chống "false-clean": script exit 2 khi backend hết credit/auth lỗi,
exit 3 khi coverage rỗng — **candidates=0 chỉ có nghĩa khi guard PASS**.

Artifacts:

| File | Nội dung |
|---|---|
| `outputs/discovery_results/dra/PA1.json` | candidates + coverage (pydantic JSON) |
| `outputs/discovery_results/dra/PA1.raw.txt` | transcript thô của agent (debug) |

---

## 8. iFinder — VA vetting (cross-check từng candidate)

VA đối chiếu code-vs-spec: "missing validation" có THẬT SỰ vắng mặt trên mọi
path reachable không (kể cả prerequisite handlers)? In ra FEASIBLE/INFEASIBLE.

```bash
cd lab/ifinder
set -a; . ./.env; set +a
export ANTHROPIC_BASE_URL=http://127.0.0.1:8787
PATH="$PWD/../ifinder-venv/bin:$PATH" \
ifinder run --scope scope_dra.json --patterns PA1 --stage vetting \
  --target dra --model deepseek-v4-pro-0813
# Tham chiếu: ~10 phút cho 2 candidates → "VA dra/PA1: 1 feasible"
```

Đọc verdict:

```bash
python3 -c "
import json
d=json.load(open('outputs/vetting_results/dra/PA1.json'))
print(d['statistics'])
for r in d['results']:
    print(r['candidate_id'], r['verdict'])
    print(' evidence:', r['evidence'][:180], '...')"
```

Kết quả tham chiếu lần chạy 24/8:

| Candidate | Site | Verdict | Ý nghĩa |
|---|---|---|---|
| DA-PA1-001 | `DiameterWireCodec.decode:96` — AVP V-flag Length 8..11 vẫn `readUnsignedInt()` đọc vendor-id ngoài biên | **FEASIBLE** | Latent defect thật; nên gate `avpLength >= 12` khi FLAG_V. Hiện decode không nằm trên ingress production (corsac parse trước) nhưng còn sống ở bench raw-path + `fromRawFrame` |
| DA-PA1-002 | `decode:100` — dataLength clamp âm | INFEASIBLE | Production không chạm codec này |

---

## 9. (Tùy chọn) Quét đủ 6 pattern

```bash
cd lab/ifinder
set -a; . ./.env; set +a
export ANTHROPIC_BASE_URL=http://127.0.0.1:8787
MODEL=deepseek-v4-pro-0813 bash scripts/reproduce_full_diameter_dra.sh
# PA1 PA2 PB1 PB2 PB3 PC1, mỗi pattern ~10-15 phút; cuối in bảng tổng hợp
```

Gợi ý ưu tiên nếu muốn tiết kiệm token: **PB2** (invalid-state — liên quan
peer-state/HBH table, đúng chất DRA), rồi **PC1** (resource exhaustion),
rồi các pattern còn lại.

EA (exploitation động — Go PoC bắn vào testbed docker-dra) **chưa** trong
runbook này: cần dựng `testbed/docker-dra` compose + Go toolchain.

---

## 10. Troubleshooting (lỗi đã gặp thật)

| Triệu chứng | Nguyên nhân | Xử lý |
|---|---|---|
| curl shim trả `<!doctype html` | gateway flap | shim tự retry — xem `/tmp/opencode/shim.log` tăng `attempt`; nếu >10 lần lỗi, đợi gateway rồi restart shim |
| `403 Token này không có quyền truy cập model claude-opus-4-5...` | iFinder default model không được cấp | luôn thêm `--model deepseek-v4-pro-0813` (hoặc flash/qwen/kimi); wrapper nhận biến `MODEL=` |
| `401 Token không hợp lệ` / `Credit balance is too low` | env chưa source, CLI gọi thẳng api.anthropic.com | `set -a; . lab/ifinder/.env; set +a` TRƯỚC khi chạy; kiểm tra `echo $ANTHROPIC_BASE_URL` |
| DRA boot fail `An error occured while establishing a peer` | port cũ chưa nhả (kill→boot quá nhanh) | `ss -tlnp \| grep 3868`, chờ 3–5s rồi chạy lại |
| seeder `connection closed during CER/CEA` + log DRA `non provisioned 127.0.0.1:<ephemeral>` | thiếu `--src-port 38680` | thêm `--src-port 38680` |
| corsac log `Application ID 16777251 does not have request with command code 257` | CER sai app-id (bench cũ) | dùng bench mới (CER app-id=0); rebuild `mvn -pl bench compile` |
| testapp trả `5001 user unknown` cho mọi ULR | prefix IMSI lệch seed | dùng `--imsi-prefix 45204020` (IMSI 16 số khớp seed) |
| seeder received=N nhưng testapp requestsTotal=0 | DRA tự trả lời — rules chưa nạp hoặc candidates rỗng | PUT lại rules; kiểm tra `[relay] decision` trong dra.log là Forward hay Reject |
| `PeerNotReadyException: peer 'hss-a' not ready` lúc lab vừa lên | testapp chưa nghe :3869 | check `curl :8086/api/health` trước; peer OPEN tự hồi sau ≤1s nhờ poll 100ms |
| claude CLI treo >2 phút không output | gateway chậm/flap | retry; xem shim.log có request nào 200 chưa |

---

## 11. Dọn dẹp (BẮT BUỘC khi xong — RAM shared giữa các worktree)

```bash
pkill -f quarkus-run                    # DRA
pkill -f sas-diameter-testapp-lab.jar   # simulator
pkill -f aibox-shim.py                  # gateway shim
pkill -f 'bin/ifinder' ; pkill -f 'claude'   # nếu còn agent treo
sleep 3
ss -tlnp | grep -E ':(3868|3869|8080|8086|8787)\b' || echo "ALL PORTS FREE"
ps -eo pid,args | grep -E 'java.*quarkus|sas-diameter|aibox' | grep -v grep || echo "no strays"
```

Trạng thái "giữ lại" hợp lệ: không gì cả — shim/testapp/DRA đều khởi động
< 30 giây nên không cần giữ chạy chờ sẵn.

---

## Tóm tắt thứ tự một phiên (cheat-sheet)

```bash
cd .../Nextgen-DRA && export JAVA_HOME=$(mise where java@zulu-25)
# 1 shim        → curl :8787 JSON OK
# 2 testapp     → curl :8086/api/health up
# 3 DRA         → curl :8080/api/peers hss-a OPEN → PUT rules applied v1
# 4 smoke       → BenchScenario 4/4, testapp req+ans 1 cặp, rc 2001/2001/5421/2001
# 5 ifinder DA  → reproduce_one_candidate... PATTERN=PA1 MODEL=deepseek-v4-pro-0813
# 6 ifinder VA  → ifinder run --stage vetting ... → FEASIBLE/INFEASIBLE
# 7 cleanup     → pkill hết, ports free
```
