# Elisa Roaming — Unified SS7 + Diameter Signaling Platform

`et.elisa:elisa-roaming` — a monorepo unifying **STP + IWF + DRA** (Elisa core
MVNO family). One Maven reactor, one build command, one shared set of
conventions (see AGENTS.md).

> **English is the default language for this README** (and root-level project
> docs). Deep-dive design/runbook documents under `docs/` may keep their
> original Vietnamese wording.

## Components

| Module | Role |
|---|---|
| `elisa-signaling-core` | Protocol-agnostic routing library (`RoutingContext`, `Matcher`, `SignalingMessage`) — frozen contracts |
| `elisa-stp` | Active-active SS7 STP (ra-jss7 M3UA/SCCP/GTT, Infinispan/JGroups clustering, SCTP-only, transit ACL + HA) |
| `elisa-iwf` | IWF TS 29.305 MAP↔Diameter (mapping table + engine; MAP leg via TCAP/ra-jss7, Diameter leg is a **client of the DRA**) |
| `elisa-dra` | Diameter routing/relay (corsac multi-peer RA, oxio/relay, screening, binding, overload, admin REST, Pg/Flyway) |
| `bench` | DRA bench seeder/harness |
| `lab/sas-diameter-testapp` | Lab HSS/AAA/PCRF simulators behind the DRA |
| `elisa-bom` | BOM pinning for downstream consumers |

## Build

- **JDK 25** (`mise zulu-25`, pinned in root `mise.toml`); Maven 3.9.9.
- From the repo root: `mvn clean test` — 7 modules, ~433 tests (core 15,
  DRA 268, STP 64, IWF 53, bench 4, lab 29); Log4j2-only enforced via the
  surefire argLine pinned in the parent POM.
- Never lower `maven.compiler.release`.

## Implemented protocol coverage (spec-complete)

Every spec listed below is **implemented in source** (not merely referenced).
Canonical 3GPP/RFC copies are kept out of the public tree.

### 3GPP TS — 14 specs

| TS | Title | Implemented in |
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

| RFC | Title | Implemented in |
|-----|---------|-----------------|
| **RFC 6733** (bis 7075) | Diameter Base Protocol | DRA `DiameterWireCodec`, `CorsacPeerFabric`, `RelayCore`; IWF `CorsacDiameterLeg` |
| **RFC 7683** | Diameter Overload Indication (DOIC) | DRA `OlrCache`, `OverloadGateImpl`, OC-Supported-Features(621)/OC-OLR(623) |
| **RFC 7944** | Diameter Routing Metric Priority (DRMP) | DRA `AvpCodes.DRMP(301)`, priority-based throttling |
| **RFC 8583** | Diameter Load Balancing | DRA `LoadCache`, Load AVP(681), load-aware LB |
| **RFC 4666** | SS7 M3UA | STP M3UA AS/ASP, routing contexts, loadshare, SLS |
| **RFC 4960** | SCTP | STP sctp-impl/backend-fstack; whole project |
| **RFC 9260** | SCTP fragmentation | STP `sctp-backend-fstack` |
| **RFC 7075** | Diameter realm-based redirect | DRA redirect (3006) handling |

### GSMA

| Spec | Title | Implementation |
|------|---------|-----------|
| **IR.88** (v28) | EPS Roaming Guidelines | DRA topology hiding, DEA behavior, per-peering allowlist, app-id filter, realm routing |

### SS7 protocol stack (STP — not owned by a single TS)

| Protocol | Standard | Implemented |
|----------|-------|-----------|
| MTP3 | Q.701–Q.703 | `SccpStackImpl.onMtp3TransferMessage`, routing label, SLS |
| SCCP | Q.711–Q.714 | Connectionless relay (UDT/XUDT/LUDT), GTT, hop counter |
| TCAP | Q.771–Q.775 | STP relay services (SSN 6/8/145); IWF dialog state (M-IWF-3) |
| GTT | Q.714 §4 | `translationFunction`, GTT rules (`ss7.json`) |
| ACL | implementation-defined | `SccpIncomingAcl`, per-OPC/SSN/GT allow-list, default-deny |

### Coverage summary

| Domain | Protocols | Standards |
|--------|-----------|-----------|
| Diameter routing | CER/CEA, DWR/DWA, DPR/DPA, relay, overload, screening | RFC 6733, 7683, 7944, 8583 |
| Diameter apps | S6a/S6d, S6c, S13, SLh, SLg, Sh, Cx/Dx, Rx, Gx, SWx, Ro | TS 29.272, 29.229, 29.214, 29.212, 29.273, 32.299 |
| MAP↔Diameter IWF | 34 entries: mobility, voice, SMS, CAMEL, LCS, SS, USSD, handover | TS 29.305, 29.002, 29.272 |
| SS7/SIGTRAN transit | M3UA, SCCP class 0/1, GTT, ACL, topology hiding | RFC 4666, 4960, 9260, Q.7xx |
| Security | IPsec (NDS/IP), realm validation, origin verification | TS 33.210, RFC 6733 §6.2 |

## Conventions (summary — full version in `AGENTS.md`)

- **Log4j2 only**, **SCTP only**, Java 25, immutable-first, LongAdder counters.
- **Peer truth law**: LISTEN ≠ ready; ready = channel up + CEA/DWA OPEN;
  fail-closed (3002), never silently drop.
- **Prove the artifact** before declaring done; resource hygiene at the end
  of every session.
- `configs/*` = operator-owned, do not clobber on deploy; `app/` ships
  outside the jars.
- Canonical spec copies and internal runbooks stay out of the public tree.

## License & Commercial Model (dual-license)

Elisa Roaming is released under a **dual-license model** (MySQL/Sidekiq pattern),
both grants held by the copyright owner (Tran Nhan): one **AGPL-3.0 Community**
license and one proprietary **Operator** license.

| Edition | License | Who | Channel |
|---|---|---|---|
| **Community** | AGPL-3.0-or-later | Free — **whole monorepo** (STP + IWF + DRA + fabric; D1=A) | Public source + AWS Marketplace free AMI |
| **Operator** | Commercial (owner-held) | Operators/SIs; production rights (incl. micro-jainslee, D2) + SLA | Private offers, license-key enforced |

**Why AGPL-3.0, not GPL/BSL/SSPL:** the DRA's Diameter transport links a local
fork of Mobius **corsac-diameter, which is AGPL-3.0** — any distributed combined
binary is legally AGPL. AGPL is therefore the only compatible *and* the
monetization lever (same posture as the Mobicents/jSS7 ecosystem). All other
deps (Quarkus, Log4j2, Jackson, Infinispan/JGroups) are Apache-2.0/LGPL —
AGPL-compatible; `micro-jainslee` is in-house and owner-held under the same
dual license (D2).

**Open source + revenue ("trả tiền"):** Community edition is the free funnel
(engineer adoption, MVNO labs); the paid Operator edition earns per-deployment
license revenue (node/MPS/year), AWS Marketplace private offers keep 20%→3%
fees, supported by L1/L2 SLA, training and deployment engineering.

Research & decision record and the AWS Marketplace go-to-market plan are
maintained privately (outside this public tree).
Owner decisions (2026-08-28): **D1 = open everything** (single all-AGPL tree,
incl. STP/fabric); **D2 = micro-jainslee dual-licensed** (AGPL/commercial);
**D3 = Mobius corsac handled by direct relationship** — no open blocker.

## Git

No remote yet — local commits are allowed once tests are green; do not push.