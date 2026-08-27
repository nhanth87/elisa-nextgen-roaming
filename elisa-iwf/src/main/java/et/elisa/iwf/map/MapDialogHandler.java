package et.elisa.iwf.map;

import java.util.Map;

/**
 * MAP leg: TCAP/MAP dialog handling via the family ra-jss7 stack.
 * Skeleton stage: interface only — the real implementation lands with the
 * STP-harness milestone and follows the ussdgw/gmlc dialog pattern
 * (stateful dialogs held in the service cluster, never in the STP transit).
 */
public interface MapDialogHandler {

    /** Invoke a MAP operation towards the HLR/MSC side. Returns dialog id. */
    long invoke(MapOp op, Map<String, String> args) throws MapLegException;

    /** Register interest in inbound MAP invocations (e.g. MAP CancelLocation). */
    void setInboundListener(InboundMapListener listener);

    @FunctionalInterface
    interface InboundMapListener {
        void onMapInvoke(MapOp op, long dialogId, Map<String, String> args);
    }

    class MapLegException extends Exception {
        public MapLegException(String message) {
            super(message);
        }
    }
}
