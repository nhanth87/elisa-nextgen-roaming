# AGENTS.md — Elisa Roaming (tree-local)

## JDK
Java 25 only — mise `zulu-25` (pinned in root `mise.toml`). Build:
`export JAVA_HOME=$(mise where java@zulu-25)`. Không bao giờ hạ `maven.compiler.release`.

## Bản sắc
Thuộc họ **Elisa** (Elisa core MVNO). Unified monorepo hợp nhất ba service cluster
trước đây: **Nextgen STP** (SS7 transit), **IWF** (TS 29.305 MAP↔Diameter), **DRA**
(Diameter routing/relay). Single parent POM, một lệnh `mvn clean test` build toàn bộ.

## Kiến trúc module

Single-reactor multi-module Maven (`et.elisa:elisa-roaming:0.1.0-SNAPSHOT`):

| Đường dẫn | Nội dung |
|---|---|
| `pom.xml` | Parent aggregator + `dependencyManagement` (Quarkus 3.37.3, micro-jainslee 1.2.0-SNAPSHOT, corsac 10.0.0-41-SNAPSHOT, Log4j2 2.26.1, Jackson 2.17.2, SCTP 2.27.32, Infinispan 15/jgroups 5.3.13/protostream 5.0.1) |
| `elisa-signaling-core/` | Protocol-agnostic routing lib (`RoutingContext`, `Matcher`, `SignalingMessage`, `RouteDecision`) — **FROZEN CONTRACTS**: không sửa tùy tiện |
| `elisa-dra/` | DRA (Quarkus + micro-jainslee, corsac multi-peer RA/relay, Pg/Flyway, admin REST) |
| `elisa-stp/` | STP (active-active SS7, ra-jss7, Infinispan/JGroups clustering, SCTP-only transport, transit ACL/HA config) |
| `elisa-iwf/` | IWF (TS 29.305 memo mapping + drafts; MAP leg qua ra-jss7/TCAP, Diameter leg là client của DRA) |
| `bench/` | DRA bench (seeder client + harness) |
| `lab/sas-diameter-testapp` | Lab HSS/AAA/PCRF simulators behind DRA |
| `elisa-bom/` | Publishable BOM for downstream consumers |
| `app/` | Static admin UI (htmx), ship outside jars |
| `configs/` | Operator-owned runtime config (`dra-*.json`, `iwf.json`) — **không clobber khi deploy** |
| `docs/` | (**private — không push public**) design, plans, runbooks, research, security-lab, pending-issues, agents, **specs (3GPP/RFC/GSMA)** |

## Luật nhà (bắt buộc, toàn monorepo)
- **Log4j2 only**: chỉ `org.apache.logging.log4j`; CẤM `java.util.logging`,
  `org.jboss.logging`, `org.slf4j`, `commons-logging` trong code sở hữu.
  `jboss-logmanager` = structural requirement im lặng của Quarkus fast-jar (giữ
  jar, không dùng facade, không thêm dep mới). Mọi facade ép vào Log4j2 bằng
  `-Dorg.jboss.logging.provider=log4j2`; plain-JVM thêm
  `-Djava.util.logging.manager=...log4j.jul.LogManager`. Surefire argLine đã ghim
  ở parent pom. Config duy nhất `configs/log4j2.xml`.
- **SCTP only**: SCTP cho mọi transport SS7/SIGTRAN và client Diameter→DRA; cấm
  TCP trong transport field; lab fallback duy nhất = NETTY_KERNEL + sctp.
- Java 25, không comment thừa, immutable-first, LongAdder counters, không blocking
  IO trên SLEE event thread.
- **Peer truth law**: LISTEN ≠ ready. Ready = channel up + CEA 2001 + watchdog hợp
  lệ. Fail-closed (3002) khi không deliver được — không bao giờ silent-drop.
- **Prove the artifact**: test xanh chưa đóng bài. Deploy thật: package fast-jar
  dist (`quarkus/`+`lib/`+jar TOGETHER) → rsync → restart (bracket-trick kill) →
  prove live bằng log lines/REAL peer status — không mtime, không UI badge một chiều.
- **Resource hygiene**: hết phiên = tắt hết JVM/process mình bật; port bind
  loopback; không để Docker/JVM mồ côi.
- Test: JUnit 5. `mvn clean test` từ root xanh trước khi báo xong — đọc "Tests run:" thật.

## Spec compliance
Danh sách đầy đủ 3GPP TS / IETF RFC / GSMA implement — xem `README.md`
(§Implemented protocol coverage). Canonical spec copies được giữ riêng tư.
Nguyên tắc: hằng số protocol phải derive/cite được từ spec;
design doc lệch spec phải sửa doc theo spec, không sửa code theo doc.

## Git
Repo riêng (chưa có remote). Commit local được phép sau khi test xanh; KHÔNG push
lên đâu cho tới khi owner khai báo remote. Commit message tiếng Anh ngắn, không
Co-authored-by AI, authorship nhanth87.