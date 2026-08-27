package et.elisa.iwf.diameter;

import java.util.Map;

/**
 * Diameter leg towards the Nextgen DRA — application-agnostic: every
 * application/command present in the corsac stack is registered, advertised
 * and decodable (see {@link DiaApps}); TS 29.305 interworking mappings across
 * all 10 Diameter apps ride on top.
 */
public interface DiameterLeg {

    /** Typed send with hop-by-hop correlation. App resolved from {@link DiaCmd}. */
    DiaResult send(DiaCmd cmd, Map<String, String> avps) throws DiaLegException;

    /** CER/CEA + watchdog health of the leg towards the DRA. */
    boolean ready();

    /** Outcome of a correlated request/answer pair. */
    record DiaResult(long hopByHopId, int resultCode) {
    }

    class DiaLegException extends Exception {
        public DiaLegException(String message) {
            super(message);
        }

        public DiaLegException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
