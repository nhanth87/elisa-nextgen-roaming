package et.elisa.iwf.mapping;

import et.elisa.iwf.diameter.DiaCmd;
import et.elisa.iwf.map.MapOp;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The TS 29.305 mapping table — the heart of the IWF.
 *
 * Coverage: full roaming interworking per 3GPP TS 29.305 (Rel-19) §7
 * + IR.88 roaming profiles. Each row pairs a MAP operation with its
 * Diameter counterpart across all 10 Diameter apps carried by the DRA.
 *
 * <p>Directions:
 * <ul>
 *   <li>{@code mapToDia=true}: IWF converts inbound MAP RfC → outbound
 *       Diameter request (S6a/S6d/S6c/S13/SLh/SLg/Sh/Rx/Gx/Cx/SWx).</li>
 *   <li>{@code diaToMap=true}: HSS-initiated Diameter request → MAP RfC
 *       (M-IWF-3: ra-jss7 TCAP/MAP leg).</li>
 * </ul>
 *
 * <p>Status progression: {@code PLANNED} → {@code MAPPED} (unit test pins
 * AVP transforms) → {@code LAB_VERIFIED} (STP↔DRA oracle passes).
 *
 * <p>Normative base: TS 29.305 §7 per-flow clauses; MAP op codes from
 * TS 29.002; Diameter commands + AVP codes from TS 29.272 / TS 29.229 /
 * TS 29.214 / TS 29.212 / TS 29.273.
 */
public final class Ts29305Table {

    private static final List<MappingEntry> ENTRIES = List.of(

            // ── S6a/S6d mobility management (TS 29.305 §7.1–§7.8) ───

            MappingEntry.of(MapOp.UPDATE_GPRS_LOCATION, DiaCmd.ULR,
                    true, false, "TS 29.305 §7.1 (S6d↔Gr Update Location)",
                    MappingEntry.Status.MAPPED,
                    AvpTransform.identity("imsi", "User-Name", 1, true),
                    AvpTransform.tbcdPlmn("plmn", "Visited-PLMN-Id", 1407, false),
                    AvpTransform.identity("sgsnNumber", "SGSN-Number", 1405, false)),

            MappingEntry.of(MapOp.SEND_AUTHENTICATION_INFO, DiaCmd.AIR,
                    true, false, "TS 29.305 §7.2 (Authentication Information)",
                    MappingEntry.Status.MAPPED,
                    AvpTransform.identity("imsi", "User-Name", 1, true),
                    AvpTransform.tbcdPlmn("plmn", "Visited-PLMN-Id", 1407, false),
                    AvpTransform.requestedVectors(1408)),

            MappingEntry.of(MapOp.PURGE_MS, DiaCmd.PUR,
                    true, false, "TS 29.305 §7.3 (Purge)",
                    MappingEntry.Status.MAPPED,
                    AvpTransform.identity("imsi", "User-Name", 1, true),
                    AvpTransform.tbcdPlmn("plmn", "Visited-PLMN-Id", 1407, false)),

            MappingEntry.planned(MapOp.INSERT_SUBSCRIBER_DATA, DiaCmd.IDR,
                    false, true, "TS 29.305 §7.4 (Subscriber Data handling)"),

            MappingEntry.planned(MapOp.DELETE_SUBSCRIBER_DATA, DiaCmd.DSR,
                    false, true, "TS 29.305 §7.4 (Subscriber Data handling)"),

            MappingEntry.planned(MapOp.CANCEL_LOCATION, DiaCmd.CLR,
                    false, true, "TS 29.305 §7.1 (Cancellation, HSS-initiated)"),

            MappingEntry.of(MapOp.NOTIFY_GPRS, DiaCmd.NOR,
                    true, true, "TS 29.305 §7.5 (Notify, bidirectional)",
                    MappingEntry.Status.MAPPED,
                    AvpTransform.identity("imsi", "User-Name", 1, true),
                    AvpTransform.tbcdPlmn("plmn", "Visited-PLMN-Id", 1407, false),
                    AvpTransform.norFlags(1411)),

            MappingEntry.planned(MapOp.RESTORE_DATA, DiaCmd.ULR,
                    true, false, "TS 29.305 §7.1 (Restore Data → S6d ULR)"),

            MappingEntry.planned(MapOp.UPDATE_LOCATION, DiaCmd.ULR,
                    true, false, "TS 29.305 §7.1 (MME Update Location → S6a ULR)"),

            MappingEntry.planned(MapOp.PROCESS_ACCESS_REQUEST, DiaCmd.AIR,
                    true, false, "TS 29.305 §7.2 (Process Access → S6a AIR)"),

            MappingEntry.planned(MapOp.NOTE_SUBSCRIBER_DATA_CHANGE, DiaCmd.IDR,
                    false, true, "TS 29.305 §7.4 (Note Sub Data → S6a IDR)"),

            // ── S6c CS Voice / SMS (TS 29.305 §7.9–§7.12) ──────────

            MappingEntry.planned(MapOp.SEND_ROUTING_INFO_FOR_CS, DiaCmd.SRR_S6C,
                    true, false, "TS 29.305 §7.9 (SRI-CS → S6c SRR)"),

            MappingEntry.planned(MapOp.PROVIDE_ROAMING_NUMBER, DiaCmd.SRR_S6C,
                    true, false, "TS 29.305 §7.10 (PRN → S6c SRR)"),

            MappingEntry.planned(MapOp.SEND_INFO_FOR_OUTGOING_SM, DiaCmd.SRR_S6C,
                    true, false, "TS 29.305 §7.11 (SIFO-SM → S6c SRR)"),

            MappingEntry.planned(MapOp.SEND_INFO_FOR_INCOMING_SM, DiaCmd.SRR_S6C,
                    true, false, "TS 29.305 §7.12 (SIFI-SM → S6c SRR)"),

            MappingEntry.planned(MapOp.REPORT_SM_DELIVERY_STATUS, DiaCmd.ASM_S6C,
                    true, false, "TS 29.305 §7.13 (SMDS → S6c ASM)"),

            MappingEntry.planned(MapOp.READY_FOR_SM, DiaCmd.SRR_S6C,
                    true, false, "TS 29.305 §7.14 (RFSM → S6c SRR)"),

            // ── S13 CAMEL / tracing (TS 29.305 §7.15–§7.17) ─────────

            MappingEntry.planned(MapOp.PROVIDE_SUBSCRIBER_INFO, DiaCmd.ECR_S13,
                    true, false, "TS 29.305 §7.15 (PSI → S13 ECR)"),

            MappingEntry.planned(MapOp.DEBUT_TRACE, DiaCmd.ECR_S13,
                    true, false, "TS 29.305 §7.16 (DT → S13 ECR)"),

            MappingEntry.planned(MapOp.ADD_CAMEL_SUBSCRIPTION_INFO, DiaCmd.ECR_S13,
                    true, false, "TS 29.305 §7.17 (ACSI → S13 ECR)"),

            // ── SLh LCS (TS 29.305 §7.18–§7.19) ────────────────────

            MappingEntry.planned(MapOp.PROVIDE_SUBSCRIBER_LOCATION, DiaCmd.PLR_SLG,
                    true, false, "TS 29.305 §7.18 (PSL → SLg PLR)"),

            MappingEntry.planned(MapOp.SUBSCRIBER_LOCATION_REPORT, DiaCmd.LRR_SLG,
                    true, false, "TS 29.305 §7.19 (SLR → SLg LRR)"),

            // ── SS (MAP-only, no Diameter counterpart in TS 29.305) ─

            MappingEntry.planned(MapOp.REGISTER_SS, DiaCmd.NOR,
                    true, false, "TS 29.305 §7.20 (SS register → S6a NOR)"),

            MappingEntry.planned(MapOp.ACTIVATE_SS, DiaCmd.NOR,
                    true, false, "TS 29.305 §7.21 (SS activate → S6a NOR)"),

            MappingEntry.planned(MapOp.DEACTIVATE_SS, DiaCmd.NOR,
                    true, false, "TS 29.305 §7.22 (SS deactivate → S6a NOR)"),

            MappingEntry.planned(MapOp.INTERROGATE_SS, DiaCmd.IDR,
                    true, false, "TS 29.305 §7.23 (SS interrogate → S6a IDR)"),

            MappingEntry.planned(MapOp.ERASE_SS, DiaCmd.DSR,
                    true, false, "TS 29.305 §7.24 (SS erase → S6a DSR)"),

            // ── USSD (MAP-only) ──────────────────────────────────────

            MappingEntry.planned(MapOp.UNSTRUCTURED_SS_REQUEST, DiaCmd.NOR,
                    true, false, "TS 29.305 §7.25 (USSD → S6a NOR)"),

            // ── Handover (MAP-only, no Diameter pair) ────────────────

            MappingEntry.planned(MapOp.PREPARE_HO, DiaCmd.NOR,
                    true, false, "TS 29.305 §7.26 (HO prep → S6a NOR)"),

            MappingEntry.planned(MapOp.PREPARE_SUBSEQUENT_HO, DiaCmd.NOR,
                    true, false, "TS 29.305 §7.27 (subseq HO → S6a NOR)"),

            MappingEntry.planned(MapOp.ALLOCATE_HANDOVER_NUMBER, DiaCmd.NOR,
                    true, false, "TS 29.305 §7.28 (AHN → S6a NOR)"),

            MappingEntry.planned(MapOp.SEND_END_SIGNAL, DiaCmd.NOR,
                    true, false, "TS 29.305 §7.29 (end signal → S6a NOR)"),

            MappingEntry.planned(MapOp.PROCESS_ACCESS_SIGNALLING, DiaCmd.NOR,
                    true, false, "TS 29.305 §7.30 (PAS → S6a NOR)"),

            MappingEntry.planned(MapOp.FORWARD_ACCESS_SIGNALLING, DiaCmd.NOR,
                    true, false, "TS 29.305 §7.31 (FAS → S6a NOR)"),

            // ── Other ────────────────────────────────────────────────

            MappingEntry.planned(MapOp.NOTE_INTERACTION, DiaCmd.NOR,
                    true, false, "TS 29.305 §7.32 (note interaction → S6a NOR)")
    );

    private static final Map<MapOp, MappingEntry> BY_MAP_OP =
            ENTRIES.stream().collect(Collectors.toUnmodifiableMap(MappingEntry::mapOp, e -> e));
    private static final Map<DiaCmd, MappingEntry> BY_DIA_CMD =
            ENTRIES.stream().collect(java.util.stream.Collectors.toMap(
                    MappingEntry::diaCmd, e -> e, (a, b) -> a));

    private Ts29305Table() {
    }

    public static List<MappingEntry> all() {
        return ENTRIES;
    }

    public static Optional<MappingEntry> forMapOp(MapOp op) {
        return Optional.ofNullable(BY_MAP_OP.get(op));
    }

    public static Optional<MappingEntry> forDiaCmd(DiaCmd cmd) {
        return Optional.ofNullable(BY_DIA_CMD.get(cmd));
    }

    /** @deprecated Use {@link #forDiaCmd(DiaCmd)} instead. */
    @Deprecated
    public static Optional<MappingEntry> forDiaOp(DiaCmd cmd) {
        return forDiaCmd(cmd);
    }
}
