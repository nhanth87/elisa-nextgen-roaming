package et.elisa.iwf.diameter;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Diameter application IDs carried by the corsac (mobius) stack, each
 * mapped to the 3GPP-assigned {@code Auth-Application-Id}. The IWF leg
 * advertises all 10 apps to the DRA via CER; the TS 29.305 mapping table
 * references the correct {@link DiaApp} per interworking row so the
 * {@link et.elisa.iwf.mapping.IwfEngine} resolves the app at dispatch time.
 *
 * <p>App IDs pinned to TS 29.272 / TS 29.214 / TS 29.229 / TS 29.336 / TS 29.273:
 * <ul>
 *   <li>S6a/S6d: 16777251 (TS 29.272)</li>
 *   <li>S6c:     16777312 (TS 29.272 §5a)</li>
 *   <li>S13:     16777252 (TS 29.272 §5e)</li>
 *   <li>SLh:     16777291 (TS 29.272 §5f)</li>
 *   <li>SLg:     16777255 (TS 29.272 §5g)</li>
 *   <li>Sh:      16777217 (TS 29.229)</li>
 *   <li>Rx:      16777236 (TS 29.214)</li>
 *   <li>Gx:      16777238 (TS 29.212)</li>
 *   <li>Cx/Dx:   16777216 (TS 29.229 §5)</li>
 *   <li>SWx:     16777265 (TS 29.273)</li>
 * </ul>
 */
public enum DiaApp {

    S6A  (16777251L, "S6a/S6d"),
    S6C  (16777312L, "S6c"),
    S13  (16777252L, "S13"),
    SLH  (16777291L, "SLh"),
    SLG  (16777255L, "SLg"),
    SH   (16777217L, "Sh"),
    RX   (16777236L, "Rx"),
    GX   (16777238L, "Gx"),
    CX_DX(16777216L, "Cx/Dx"),
    SWX  (16777265L, "SWx");

    private final long appId;
    private final String diaName;

    DiaApp(long appId, String diaName) {
        this.appId = appId;
        this.diaName = diaName;
    }

    public long appId() {
        return appId;
    }

    public String diaName() {
        return diaName;
    }

    private static final Map<Long, DiaApp> BY_ID =
            java.util.Arrays.stream(values())
                    .collect(Collectors.toUnmodifiableMap(DiaApp::appId, a -> a));

    public static Optional<DiaApp> byId(long appId) {
        return Optional.ofNullable(BY_ID.get(appId));
    }

    public static Set<Long> allIds() {
        return BY_ID.keySet();
    }
}
