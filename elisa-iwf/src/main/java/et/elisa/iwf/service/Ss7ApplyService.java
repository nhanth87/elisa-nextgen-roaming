package et.elisa.iwf.service;

import et.elisa.iwf.admin.LinkStatusService;

import com.microjainslee.core.MicroSleeContainer;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * MAP/TCAP RA lifecycle management. Mirrors the GMLC {@code Ss7ApplyService}
 * pattern — apply, start, stop, tearDown.
 *
 * <p>Currently a skeleton: M-IWF-3 will wire ra-jss7
 * {@code Ss7ResourceAdaptor} here when the real MAP leg is implemented.</p>
 */
@ApplicationScoped
public class Ss7ApplyService {

    private static final Logger LOG = LogManager.getLogger(Ss7ApplyService.class);

    @Inject
    MicroSleeContainer container;

    @Inject
    LinkStatusService linkStatus;

    private volatile boolean active;

    public String apply() {
        return tearDown() + ";" + wireIfConfigured();
    }

    public String start() {
        return active ? apply() : wireIfConfigured();
    }

    public String stop() {
        return tearDown();
    }

    public String tearDown() {
        linkStatus.clearMap();
        if (!active) {
            return "ss7-drained=noop";
        }
        active = false;
        LOG.info("ss7-drained=ok (skeleton — no real RA to deactivate)");
        return "ss7-drained=ok";
    }

    /**
     * Wire the MAP RA if configured. Currently a skeleton — logs intent
     * and marks linkStatus. M-IWF-3 will create the real
     * {@code Ss7ResourceAdaptor} here.
     */
    public String wireIfConfigured() {
        if (active) {
            return "ss7=already-active";
        }
        active = true;
        String detail = "ss7=wired(skeleton);source=config";
        linkStatus.setMapApplied(detail);
        LOG.info("SS7 boot: {} (MAP leg skeleton until M-IWF-3)", detail);
        return detail;
    }

    public boolean isActive() {
        return active;
    }
}
