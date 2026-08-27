# Contribution: Diameter protocol support (DRA / S6a relay target)

Adds **Diameter (RFC 6733)** as a first-class protocol alongside PFCP and
GTP-C, plus a complete scan target for the open-source **Elisa Nextgen DRA**
(Java 25 relay on micro-jainslee). Authored for upstream donation; also used
to audit the DRA itself.

## Files

| Path | Purpose |
|------|---------|
| `schema/diameter/raw/IE/*.json` | 24 routing-relevant AVPs (RFC 6733 §4 + TS 29.272) |
| `schema/diameter/raw/Message/*.json` | 10 messages: CER/CEA, DWR/DWA, ULR/ULA, AIR/AIA, PUR/PUA |
| `schema/diameter/generated/*normalized.json` | normalized KB (same generator shape as gtpc) |
| `procedure/diameter/*.json` | 5 procedures: capabilities exchange, watchdog, S6a ULR/AIR/PUR through a relay hop |
| `scope/diameter/scope_dra.json` | target `dra`: dra-core/dra-ra/dra-app Java sources |
| `testbed/docker-dra/` | compose: DRA (:3868 ingress, :8080 admin) + HSS/SAS simulator (:3869); see `artifacts/README.md` for the three build inputs |
| `scripts/check_diameter_kb.py` | offline consistency gate: schema ↔ procedures ↔ scope, no LLM |
| `scripts/reproduce_one_candidate_diameter_dra.sh` | one-pattern static run with backend-health guard |
| `scripts/reproduce_full_diameter_dra.sh` | all six patterns + summary table |
| `src/ifinder/prompts.py`, `src/ifinder/models.py` | minimal registry diff: `diameter` entries in `PROTOCOL_LABELS/NFS/POC`, `NetworkFunction.DRA` |

## Provenance & license

- KB re-authored from public specifications (RFC 6733, 3GPP TS 29.272 Rel-19);
  no content copied from other iFinder artifacts beyond file/field conventions.
- New files follow the repository's PolyForm Noncommercial license.
- `target/dra_code` (not committed) is a symlink to the Nextgen-DRA working copy
  (GPLv3/AGPLv3 dual-licensed project); it is not part of this artifact.

## Run

    pip install -e src
    python3 scripts/check_diameter_kb.py                       # offline gate
    PATTERN=PA1 bash scripts/reproduce_one_candidate_diameter_dra.sh

The one-candidate wrapper fails loudly (exit 2) if the Claude backend is
unfunded/misconfigured — an empty result JSON must never read as "clean".
EA live exploitation additionally needs `testbed/docker-dra` built
(three artifact inputs documented in its README) and docker compose.

## Status

- Offline: loaders, consistency gate, scope resolution verified.
- Live DA/VA/EA on the DRA: pending funded credentials (backend returned
  "Credit balance is too low" during bring-up).
