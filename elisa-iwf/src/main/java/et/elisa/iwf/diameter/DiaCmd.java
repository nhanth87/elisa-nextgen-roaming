package et.elisa.iwf.diameter;

import java.util.Set;

/**
 * Diameter command codes used by TS 29.305 interworking, grouped by
 * application. Each constant carries its {@link DiaApp} so the IWF leg
 * can resolve the correct {@code Auth-Application-Id} at dispatch time.
 *
 * <p>Command codes pinned to TS 29.272 §8 (S6a/S6d), TS 29.272 §8a (S6c),
 * TS 29.272 §8b (S13), TS 29.272 §8c (SLh), TS 29.272 §8d (SLg),
 * TS 29.229 §5 (Sh/Cx/Dx), TS 29.214 §5 (Rx), TS 29.212 §5 (Gx),
 * TS 29.273 §5 (SWx).
 */
public enum DiaCmd {

    // ── S6a/S6d (TS 29.272 §8) ──────────────────────────────────────
    ULR (316, "Update-Location",         true,  DiaApp.S6A),
    CLR (317, "Cancel-Location",         false, DiaApp.S6A),
    AIR (318, "Authentication-Info",     true,  DiaApp.S6A),
    IDR (319, "Insert-Subscriber-Data",  false, DiaApp.S6A),
    DSR (320, "Delete-Subscriber-Data",  false, DiaApp.S6A),
    PUR (321, "Purge-UE",                true,  DiaApp.S6A),
    NOR (323, "Notify",                  true,  DiaApp.S6A),

    // ── S6c (TS 29.272 §8a) ─────────────────────────────────────────
    SRR_S6C (324, "Send-Routing-Info-CS",    true,  DiaApp.S6C),
    ASM_S6C (325, "Alert-Service-Centre",    true,  DiaApp.S6C),

    // ── S13 (TS 29.272 §8b) ─────────────────────────────────────────
    ECR_S13 (324, "Entity-Check",            true,  DiaApp.S13),

    // ── SLh (TS 29.272 §8c) ─────────────────────────────────────────
    RIR_SLH (8388630, "Provide-Subscr-Info", true,  DiaApp.SLH),

    // ── SLg (TS 29.272 §8d) ─────────────────────────────────────────
    PLR_SLG (8388624, "Provide-Location",    true,  DiaApp.SLG),
    LRR_SLG (8388625, "Location-Report",     true,  DiaApp.SLG),

    // ── Sh (TS 29.229 §5) ───────────────────────────────────────────
    UDR_SH (306, "User-Data",                true,  DiaApp.SH),
    SNR_SH (307, "Server-Notification",      true,  DiaApp.SH),
    PUR_SH (308, "Profile-Update",           true,  DiaApp.SH),

    // ── Rx (TS 29.214 §5) ───────────────────────────────────────────
    AAR_RX (265, "AA-Request",              true,  DiaApp.RX),
    STR_RX (275, "Session-Termination",     true,  DiaApp.RX),

    // ── Gx (TS 29.212 §5) ───────────────────────────────────────────
    CCR_GX (272, "Credit-Control",          true,  DiaApp.GX),

    // ── Cx/Dx (TS 29.229 §5) ───────────────────────────────────────
    UAR_CX (300, "User-Authorization",      true,  DiaApp.CX_DX),
    LIR_CX (302, "Location-Info",           true,  DiaApp.CX_DX),
    SAR_CX (301, "Server-Assignment",       true,  DiaApp.CX_DX),

    // ── SWx (TS 29.273 §5) ──────────────────────────────────────────
    MAR_SWX (303, "Multimedia-Auth",        true,  DiaApp.SWX);

    private final int cmdCode;
    private final String diaName;
    private final boolean retryable;
    private final DiaApp app;

    DiaCmd(int cmdCode, String diaName, boolean retryable, DiaApp app) {
        this.cmdCode = cmdCode;
        this.diaName = diaName;
        this.retryable = retryable;
        this.app = app;
    }

    public int cmdCode() {
        return cmdCode;
    }

    public String diaName() {
        return diaName;
    }

    public boolean retryable() {
        return retryable;
    }

    public DiaApp app() {
        return app;
    }

    public long appId() {
        return app.appId();
    }

    // ── Convenience constants (replacing DiaOp.*_CMD and DiaOp.S6A_APP_ID) ──

    public static final long S6A_APP_ID = DiaApp.S6A.appId();

    public static final int ULR_CMD = ULR.cmdCode();
    public static final int CLR_CMD = CLR.cmdCode();
    public static final int AIR_CMD = AIR.cmdCode();
    public static final int IDR_CMD = IDR.cmdCode();
    public static final int DSR_CMD = DSR.cmdCode();
    public static final int PUR_CMD = PUR.cmdCode();
    public static final int NOR_CMD = NOR.cmdCode();

    /** All S6a/S6d command codes. */
    public static Set<Integer> s6aCmdCodes() {
        return Set.of(ULR.cmdCode(), AIR.cmdCode(), PUR.cmdCode(),
                IDR.cmdCode(), DSR.cmdCode(), CLR.cmdCode(), NOR.cmdCode());
    }

    /** All command codes across every Diameter app. */
    public static Set<Integer> allCmdCodes() {
        return java.util.Arrays.stream(values())
                .map(DiaCmd::cmdCode)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
