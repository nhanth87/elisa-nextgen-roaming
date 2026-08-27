package et.elisa.iwf.bootstrap;

import java.util.Objects;

import com.microjainslee.api.SleeEvent;
import com.microjainslee.core.MicroSleeContainer;

import et.elisa.iwf.diameter.CorsacDiameterLeg;
import et.elisa.iwf.ra.IwfAnswerEvent;
import et.elisa.iwf.ra.IwfRaEndpoint;
import et.elisa.iwf.ra.IwfRequestEvent;
import et.elisa.iwf.sbb.IwfDiaSbb;
import et.elisa.iwf.sbb.IwfRelayCore;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Strict micro-jainslee wiring for the IWF Diameter plane: container start,
 * SBB type registration ($Concrete), IES dispatcher, event→SBB mapping and
 * RA endpoint activation — the family pattern (DraBootstrap, ussdgw/gmlc).
 */
public final class IwfSleeBootstrap implements AutoCloseable {

    private static final Logger LOG = LogManager.getLogger(IwfSleeBootstrap.class);
    static final String DIA_SBB = "IwfDiaSbb";

    private final MicroSleeContainer container;
    private final IwfRelayCore core;
    private final CorsacDiameterLeg leg;
    private volatile IwfRaEndpoint endpoint;
    private volatile boolean started;

    public IwfSleeBootstrap(MicroSleeContainer container, IwfRelayCore core,
                            CorsacDiameterLeg leg) {
        this.container = Objects.requireNonNull(container, "container");
        this.core = Objects.requireNonNull(core, "core");
        this.leg = Objects.requireNonNull(leg, "leg");
    }

    public synchronized void init() {
        if (started) {
            return;
        }
        started = true;
        if (container.getState() != MicroSleeContainer.State.STARTED) {
            container.start();
        }
        container.registerSbbType(IwfDiaSbb.class, () -> new IwfDiaSbb.$Concrete(core));
        container.createIesDispatcher();
        container.mapEventToSbb(IwfRequestEvent.class, DIA_SBB);
        container.mapEventToSbb(IwfAnswerEvent.class, DIA_SBB);

        endpoint = new IwfRaEndpoint(leg::sendOnLink);
        container.registerRa(endpoint, endpoint);
        if (!endpoint.isStarted()) {
            throw new IllegalStateException("iwf ra endpoint did not activate");
        }
        leg.attachEndpoint(endpoint);
        LOG.info("[iwf-bootstrap] strict micro-jainslee wiring complete (sbb={}, ra={})",
                DIA_SBB, IwfRaEndpoint.RA_NAME);
    }

    /** Test/RA entry: feed an ingress event through the container path. */
    public void ingest(SleeEvent event) {
        IwfRaEndpoint ep = endpoint;
        if (ep == null) {
            throw new IllegalStateException("bootstrap not initialized");
        }
        ep.onRaIngress(event);
    }

    @Override
    public synchronized void close() {
        IwfRaEndpoint ep = endpoint;
        endpoint = null;
        if (ep != null) {
            ep.deactivate();
        }
        if (container.getState() == MicroSleeContainer.State.STARTED) {
            container.stop();
        }
        started = false;
    }

    public IwfRaEndpoint endpoint() {
        return endpoint;
    }

    public IwfRelayCore core() {
        return core;
    }
}
