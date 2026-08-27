package et.elisa.iwf.mapping;

/**
 * Structured AVP-level interworking step — replaces the skeleton text notes.
 *
 * <p>The engine is the single source of truth for <em>which</em> AVPs ride on
 * <em>which</em> command and whether they are mandatory; the Diameter leg only
 * encodes the selected transforms onto the typed request. Every {@code
 * diaAvpCode}/{@code diaAvpName} is TS 29.272-anchored so a row can be checked
 * against the spec without reading code. Encoding itself goes through the
 * typed corsac setters (keyed by {@link #diaAvpName}), so wire correctness does
 * not depend on the informational code.</p>
 */
public record AvpTransform(String mapSource, String diaAvpName, int diaAvpCode,
                           TransformKind kind, boolean required) {

    /** How the MAP information element becomes the Diameter AVP value. */
    public enum TransformKind {
        /** Value passes through unchanged (e.g. IMSI → User-Name). */
        IDENTITY,
        /** PLMN string → 3-octet TBCD (Visited-PLMN-Id, TS 24.301 §9.9.3.12). */
        TBCD_PLMN,
        /** Requested MAP vectors → Requested-EUTRAN-Authentication-Info count. */
        REQUESTED_VECTORS,
        /** Notify-GPRS-Type → NOR-Flags bitmask. */
        NOR_FLAGS
    }

    public AvpTransform {
        if (mapSource == null || mapSource.isBlank()) {
            throw new IllegalArgumentException("mapSource required");
        }
        if (diaAvpName == null || diaAvpName.isBlank()) {
            throw new IllegalArgumentException("diaAvpName required");
        }
        if (kind == null) {
            throw new IllegalArgumentException("kind required");
        }
    }

    public static AvpTransform identity(String mapSource, String diaAvpName, int code,
                                         boolean required) {
        return new AvpTransform(mapSource, diaAvpName, code, TransformKind.IDENTITY, required);
    }

    public static AvpTransform tbcdPlmn(String mapSource, String diaAvpName, int code,
                                        boolean required) {
        return new AvpTransform(mapSource, diaAvpName, code, TransformKind.TBCD_PLMN, required);
    }

    public static AvpTransform requestedVectors(int code) {
        return new AvpTransform("vectors", "Requested-EUTRAN-Authentication-Info", code,
                TransformKind.REQUESTED_VECTORS, false);
    }

    public static AvpTransform norFlags(int code) {
        return new AvpTransform("notifyType", "NOR-Flags", code, TransformKind.NOR_FLAGS, false);
    }
}
