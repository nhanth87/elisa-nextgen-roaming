package et.elisa.iwf.map;

import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Skeleton no-op MAP leg — wired to ra-jss7 TCAP/MAP in M-IWF-3. */
public final class NoopMapLeg implements MapDialogHandler {

    private static final Logger LOG = LogManager.getLogger(NoopMapLeg.class);

    private volatile InboundMapListener listener;

    @Override
    public long invoke(MapOp op, Map<String, String> args) throws MapLegException {
        LOG.info("[iwf-map] (skeleton) invoke {} args={} -> not wired to TCAP yet",
                op, args.keySet());
        throw new MapLegException("MAP leg not wired yet (skeleton)");
    }

    @Override
    public void setInboundListener(InboundMapListener listener) {
        this.listener = listener;
        LOG.info("[iwf-map] inbound listener registered (skeleton)");
    }

    public InboundMapListener listener() {
        return listener;
    }
}
