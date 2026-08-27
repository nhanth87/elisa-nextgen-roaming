# Nextgen STP — SCCP N-N relay lab (single STP, two external peers)

Reproduces and verifies the SCCP N-N relay data plane: two external peer nodes
(sim-a OPC 2, sim-b OPC 3) exchange SCCP traffic through a single STP node
(PC 10). The STP transit plane relaying between them is configured so that any
non-local destination point code is relayed (SCCP `canRelay` + `removeSpc`) and
the two peer GT ranges are translated to the opposite peer's point code
(bidirectional GTT), with a default-deny ingress ACL whitelisting the two peer
OPCs.

## Topology

```
           ┌───────────────────────────── STP PC=10 ─────────────────────────────┐
 sim-a ────┤  SCTP server :8021  AS-A (IPSP server) ── route DPC 2          [OPC 2]
 OPC 2     │  SCCP local PC 10 · reachable {2,3} · canRelay + removeSpc + ACL   │
 :8022     │  GTT: 29190003/* → DPC 3/SSN 8   ·   29190002/* → DPC 2/SSN 8      │
 sim-b ────┤  SCTP server :8023  AS-B (IPSP server) ── route DPC 3          [OPC 3]
 OPC 3     └──────────────────────────────────────────────────────────────────────┘
 :8024
```

## Config files

- `configs/ss7.json` — jSS7 stack (SCTP/M3UA/SCCP/TCAP): two server links,
  two AS (AS-A→DPC 2, AS-B→DPC 3), STP local PC 10, bidirectional `remote` GTT
  rules, single relay service SSN 8.
- `configs/stp.json` — transit/ACL posture: `transit.enabled=true`,
  `removeSpc=true`, default-deny ACL whitelisting OPC 2 (`29190003*`) and
  OPC 3 (`29190002*`). Loaded by `StpTransitApplyService` and applied to the
  ra-jss7 adaptor (`setStpTransitProfile`) before it activates.

## Test

`NnRelayConfigTest` (in `tools/stp-test/…/relay/`) loads both configs through
the real loaders (`Ss7ConfigLoader`, `StpTransitConfigLoader`) — so a schema or
topology typo fails the build — and asserts:

- STP PC 10, reachable peers {2,3}, two server links, two AS + two M3UA routes;
- bidirectional GTT: B's GT `29190003*` → DPC 3 / SSN 8, A's GT `29190002*` →
  DPC 2 / SSN 8;
- transit profile: `transitEnabled`, `removeSpcOnRelay`, `aclEnabled` with the
  two peer OPCs whitelisted.

```bash
mise exec java@zulu-25 -- mvn test                # deterministic, JVM-only
```

The wire-level send (SCCP UDT A→B / B→A) is exercised end-to-end with the
jSS7 simulator peers — see `tools/ss7-simulator/README.md`.

## Build / package / run

```bash
./dist/package.sh          # mvn package -Pjvm-harness → target/quarkus-app/quarkus-run.jar
./dist/run.sh              # boot STP with configs/ss7.json + configs/stp.json
./tools/ss7-simulator/run.sh up    # start sim-a + sim-b peers
./tools/ss7-simulator/run.sh status
./tools/ss7-simulator/run.sh down
```

On boot the STP logs the applied posture
(`STP transit profile applied: relay=… removeSpc=… ha=… aclPeers=2`) and, once
both peers connect, `LinkStatusService` reports `sctp.associationUp`,
`m3ua.asActive` and `transit.detail=transit=on…` which the admin UI surfaces via
`/admin/ss7` and `/admin/transit`.

> F-STACK note: `configs/ss7.json` selects `FSTACK_DPDK` (the backend this
> module links — `sctp-backend-fstack`). A lab JVM must provide
> `lib/libsctp_fstack.so`; without it the STP still boots but the SS7 wire is
> skipped (logged as `SS7 boot wire failed … lab may run without M3UA`).