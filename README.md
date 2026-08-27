# Elisa Roaming — Unified SS7 + Diameter Signaling Platform

`et.elisa:elisa-roaming` — monorepo hợp nhất **STP + IWF + DRA** (họ Elisa core
MVNO). Một reactor Maven, một lệnh build, một bộ docs/specs/lessons dùng chung.

## Thành phần

| Module | Vai trò |
|---|---|
| `elisa-signaling-core` | Routing lib protocol-agnostic (`RoutingContext`, `Matcher`, `SignalingMessage`) — frozen contracts |
| `elisa-stp` | Active-active SS7 STP (ra-jss7 M3UA/SCCP/GTT, Infinispan/JGroups clustering, SCTP-only, transit ACL + HA) |
| `elisa-iwf` | IWF TS 29.305 MAP↔Diameter (mapping table + engine; MAP leg TCAP/ra-jss7, Diameter leg là **client của DRA**) |
| `elisa-dra` | Diameter routing/relay (corsac multi-peer RA, oxio/relay, screening, binding, overload, admin REST, Pg/Flyway) |
| `bench` | DRA bench seeder/harness |
| `lab/sas-diameter-testapp` | Lab HSS/AAA/PCRF simulators behind DRA |
| `elisa-bom` | BOM pinning cho downstream |

## Build

- **JDK 25** (`mise zulu-25`, pin trong root `mise.toml`); Maven 3.9.9.
- Từ root: `mvn clean test` — 7 module, ~433 test (core 15, DRA 268, STP 64,
  IWF 53, bench 4, lab 29), Log4j2-only qua surefire argLine ghim ở parent.
- Không bao giờ hạ `maven.compiler.release`.

## Implemented protocol coverage (spec-complete)

Mọi spec dưới đây đều **implement trong source** (không chỉ ghi reference).
Bản sao tài liệu chuẩn: `docs/specs/` (xem `docs/specs/README.md`).

### 3GPP TS — 14 specs

| TS | Tiêu đề | Implement trong |
|----|---------|-----------------|
| **TS 29.305** (Rel-19) | IWF between MAP and Diameter | `Ts29305Table` (34 entries), `IwfEngine`, `MapOp`, `DiaCmd`, `DiaApp` |
| **TS 29.002** (Rel-19) | MAP | `MapOp.java` (35 op codes: updateLocation, cancelLocation, insertSubscriberData, purgeMS, sendAuthenticationInfo, restoreData, notifyGPRS, sendRoutingInfo, provideSubscriberNumber, readyForSM, sendRoutingInfoForSM, activate/deactivateSS, register/erase/interrogateSS, processUnstructured(SS), ussdRequest, statusReport, alertServiceCentre, sendIMSI…) |
| **TS 29.272** (Rel-19) | S6a/S6d/S13/SLh/SLg | `DiaCmd`/`DiaApp`; DRA routing/screening/binding; testapp handlers |
| **TS 29.229** (Rel-19) | Sh/Cx/Dx (IMS) | `DiaCmd`/`DiaApp`; testapp `CxDxHandler`, `ShHandler` |
| **TS 29.214** (Rel-19) | Rx (Policy) | `DiaCmd`/`DiaApp`; testapp `RxHandler` |
| **TS 29.212** (Rel-19) | Gx (PCC) | `DiaCmd`/`DiaApp`; testapp `GxHandler` |
| **TS 29.273** (Rel-19) | SWx | `DiaCmd`/`DiaApp` |
| **TS 29.213** (Rel-19) | PCC & Rx interaction | DRA binding resolution (framed-IP + APN) |
| **TS 29.328** (Rel-19) | HSS enhanced S6c | `DiaApp` (S6c app 16777312) |
| **TS 32.299** (Rel-19) | Diameter charging (Ro/Rf) | `DiaApp` (Ro app 4); testapp `RoHandler` |
| **TS 24.301** (Rel-19) | NAS (PLMN encoding) | `Plmn`, `AvpTransform.PlmnToTbcd` |
| **TS 23.003** (Rel-19) | Numbering/addressing | DRA realm parsing (MCC/MNC) |
| **TS 23.002** (Rel-19) | Network architecture | DRA routing context (EPC entities/reference points) |
| **TS 33.210** (Rel-19) | Network domain security | DRA security/TLS decision record |

### IETF RFC — 8 specs

| RFC | Tiêu đề | Implement trong |
|-----|---------|-----------------|
| **RFC 6733** (bis 7075) | Diameter Base Protocol | DRA `DiameterWireCodec`, `CorsacPeerFabric`, `RelayCore`; IWF `CorsacDiameterLeg` |
| **RFC 7683** | Diameter Overload Indication (DOIC) | DRA `OlrCache`, `OverloadGateImpl`, OC-Supported-Features(621)/OC-OLR(623) |
| **RFC 7944** | Diameter Routing Metric Priority (DRMP) | DRA `AvpCodes.DRMP(301)`, priority-based throttling |
| **RFC 8583** | Diameter Load Balancing | DRA `LoadCache`, Load AVP(681), load-aware LB |
| **RFC 4666** | SS7 M3UA | STP M3UA AS/ASP, routing contexts, loadshare, SLS |
| **RFC 4960** | SCTP | STP sctp-impl/backend-fstack; toàn bộ project |
| **RFC 9260** | SCTP fragmentation | STP `sctp-backend-fstack` |
| **RFC 7075** | Diameter realm-based redirect | DRA redirect (3006) handling |

### GSMA

| Spec | Tiêu đề | Implement |
|------|---------|-----------|
| **IR.88** (v28) | EPS Roaming Guidelines | DRA topology hiding, DEA behavior, allowlist per peering, app-id filter, realm routing |

### SS7 protocol stack (STP — không nằm trong 1 TS duy nhất)

| Protocol | Chuẩn | Implement |
|----------|-------|-----------|
| MTP3 | Q.701–Q.703 | `SccpStackImpl.onMtp3TransferMessage`, routing label, SLS |
| SCCP | Q.711–Q.714 | Connectionless relay (UDT/XUDT/LUDT), GTT, hop counter |
| TCAP | Q.771–Q.775 | STP relay services (SSN 6/8/145); IWF dialog state (M-IWF-3) |
| GTT | Q.714 §4 | `translationFunction`, GTT rules (`ss7.json`) |
| ACL | implementation-defined | `SccpIncomingAcl`, per-OPC/SSN/GT allow-list, default-deny |

### Tóm tắt coverage

| Domain | Protocols | Standards |
|--------|-----------|-----------|
| Diameter routing | CER/CEA, DWR/DWA, DPR/DPA, relay, overload, screening | RFC 6733, 7683, 7944, 8583 |
| Diameter apps | S6a/S6d, S6c, S13, SLh, SLg, Sh, Cx/Dx, Rx, Gx, SWx, Ro | TS 29.272, 29.229, 29.214, 29.212, 29.273, 32.299 |
| MAP↔Diameter IWF | 34 entries: mobility, voice, SMS, CAMEL, LCS, SS, USSD, handover | TS 29.305, 29.002, 29.272 |
| SS7/SIGTRAN transit | M3UA, SCCP class 0/1, GTT, ACL, topology hiding | RFC 4666, 4960, 9260, Q.7xx |
| Security | IPsec (NDS/IP), realm validation, origin verification | TS 33.210, RFC 6733 §6.2 |

## Quy ước (tóm tắt — đầy đủ ở `AGENTS.md`)

- **Log4j2 only**, **SCTP only**, Java 25, immutable-first, LongAdder counters.
- **Peer truth law**: LISTEN ≠ ready; ready = channel up + CEA/DWA OPEN; fail-closed
  (3002), không silent-drop.
- **Prove the artifact** trước khi báo xong; resource hygiene cuối phiên.
- `configs/*` = operator-owned, không clobber khi deploy; `app/` ship ngoài jars.
- Spec source: `docs/specs/`; lesson vận hành: `docs/agents/lessons.md`.

## Git
Chưa có remote — commit local được phép sau khi test xanh; không push.