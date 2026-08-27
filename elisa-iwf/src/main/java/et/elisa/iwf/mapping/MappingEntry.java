package et.elisa.iwf.mapping;

import et.elisa.iwf.diameter.DiaCmd;
import et.elisa.iwf.map.MapOp;

import java.util.List;

/**
 * One row of the TS 29.305 mapping table: a MAP operation and the Diameter
 * command it interworks with, plus the AVP/IE level mapping plan.
 *
 * @param mapOp        MAP operation (TS 29.002 op code)
 * @param diaCmd       Diameter command (carries its {@link et.elisa.iwf.diameter.DiaApp})
 * @param mapToDia     true when the IWF converts an inbound MAP RfC into an
 *                     outbound Diameter request (e.g. Gr UpdateGprsLocation
 *                     → S6d ULR)
 * @param diaToMap     true for the reverse direction (e.g. HSS-initiated
 *                     CLR → MAP CancelLocation)
 * @param specRef      normative reference (TS 29.305 clause)
 * @param status       PLANNED until the AVP-level mapping is implemented and
 *                     lab-verified
 * @param transforms   ordered AVP-level mapping steps, structured and
 *                     TS 29.272-anchored (M-IWF-2). The client-sendable
 *                     direction carries its transforms here; the DIA→MAP
 *                     direction is populated when the MAP leg lands (M-IWF-3).
 */
public record MappingEntry(MapOp mapOp, DiaCmd diaCmd, boolean mapToDia,
                            boolean diaToMap, String specRef, Status status,
                            java.util.List<AvpTransform> transforms) {

    public enum Status { PLANNED, MAPPED, LAB_VERIFIED }

    public MappingEntry {
        transforms = List.copyOf(transforms);
    }

    public static MappingEntry planned(MapOp mapOp, DiaCmd diaCmd,
                                        boolean mapToDia, boolean diaToMap,
                                        String specRef,
                                        AvpTransform... transforms) {
        return new MappingEntry(mapOp, diaCmd, mapToDia, diaToMap, specRef,
                Status.PLANNED, java.util.Arrays.asList(transforms));
    }

    public static MappingEntry of(MapOp mapOp, DiaCmd diaCmd,
                                   boolean mapToDia, boolean diaToMap,
                                   String specRef, Status status,
                                   AvpTransform... transforms) {
        return new MappingEntry(mapOp, diaCmd, mapToDia, diaToMap, specRef,
                status, java.util.Arrays.asList(transforms));
    }

    /** The Diameter application ID (auth-application-id) for this mapping. */
    public long appId() {
        return diaCmd.appId();
    }
}
