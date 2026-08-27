package et.elisa.iwf.mapping;

import et.elisa.iwf.diameter.DiaCmd;

import java.util.List;

/**
 * Pure dispatch over the TS 29.305 table. Produces request *drafts* — the
 * structured list of {@link AvpTransform}s the legs materialise onto the
 * wire (Diameter via corsac, MAP via the ra-jss7 TCAP stack). The engine is the
 * single source of truth for which AVPs ride on which command and which
 * Diameter application owns each mapping row.
 */
public final class IwfEngine {

    /** A Diameter request draft produced from a mapped MAP operation. */
    public record DiaRequestDraft(DiaCmd diaCmd, long appId,
                                  List<AvpTransform> transforms) {

        public DiaRequestDraft {
            transforms = List.copyOf(transforms);
        }
    }

    /** A MAP operation draft produced from a mapped Diameter command. */
    public record MapRequestDraft(et.elisa.iwf.map.MapOp mapOp,
                                  List<AvpTransform> transforms) {

        public MapRequestDraft {
            transforms = List.copyOf(transforms);
        }
    }

    public java.util.Optional<DiaRequestDraft> mapToDiameter(
            et.elisa.iwf.map.MapOp op, java.util.Map<String, String> mapArgs) {
        return Ts29305Table.forMapOp(op)
                .filter(MappingEntry::mapToDia)
                .map(e -> new DiaRequestDraft(e.diaCmd(), e.appId(),
                        e.transforms()));
    }

    public java.util.Optional<MapRequestDraft> diameterToMap(
            DiaCmd cmd, java.util.Map<String, String> diaArgs) {
        return Ts29305Table.forDiaCmd(cmd)
                .filter(MappingEntry::diaToMap)
                .map(e -> new MapRequestDraft(e.mapOp(), e.transforms()));
    }

    /**
     * Single source of truth for the Diameter leg: which AVP transforms apply
     * when the IWF emits a client request for {@code cmd} (the MAP→DIA direction).
     * The leg encodes these onto the typed request.
     */
    public java.util.Optional<java.util.List<AvpTransform>> clientTransformsFor(DiaCmd cmd) {
        return Ts29305Table.forDiaCmd(cmd)
                .filter(MappingEntry::mapToDia)
                .map(MappingEntry::transforms);
    }
}
