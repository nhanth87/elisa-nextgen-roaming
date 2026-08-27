package et.elisa.iwf.diameter;

import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.mobius.software.telco.protocols.diameter.ApplicationIDs;

/**
 * Registry of every Diameter application carried by the corsac (mobius)
 * stack. The list is built by reflection over corsac's
 * {@link ApplicationIDs} constants — single source of truth, so a corsac
 * upgrade automatically extends the IWF leg without code changes here.
 *
 * <p>Well-known command codes per application are keyed by
 * {@link DiaApp#appId()} for decoding inbound requests.
 */
public final class DiaApps {

    /** Well-known command codes per application; empty = any-command leg. */
    private static final Map<Long, Set<Integer>> CMD_CODES = Map.of(
            DiaApp.S6A.appId(), DiaCmd.s6aCmdCodes(),
            (long) ApplicationIDs.CREDIT_CONTROL, Set.of(272),   // Ro CCR/CCA
            (long) ApplicationIDs.ACCOUNTING, Set.of(271),       // Rf ACR/ACA
            (long) ApplicationIDs.GX, Set.of(272),
            (long) ApplicationIDs.RX, Set.of(265, 274),          // AA/AAA, ASR/ASA
            (long) ApplicationIDs.SY, Set.of(838, 839),          // SLR/SLA, SSN/SNA
            (long) ApplicationIDs.S6B, Set.of(265, 275),         // AA/AAA, ST/STA
            (long) ApplicationIDs.S13, Set.of(334),              // CIR/CIA
            (long) ApplicationIDs.NASREQ, Set.of(265, 271, 272));

    private DiaApps() {
    }

    public static List<DiaApp> all() {
        return List.of(DiaApp.values());
    }

    public static Optional<DiaApp> byId(long appId) {
        return DiaApp.byId(appId);
    }

    public static String nameOf(long appId) {
        return byId(appId).map(DiaApp::diaName).orElse("app-" + appId);
    }

    public static boolean supported(long appId) {
        return DiaApp.byId(appId).isPresent();
    }

    /** Command codes the leg expects for an app; empty set = any-command. */
    public static Set<Integer> cmdCodesOf(long appId) {
        return CMD_CODES.getOrDefault(appId, Set.of());
    }

    public static int count() {
        return DiaApp.values().length;
    }
}
