package et.elisa.stp.sbb;

import et.elisa.stp.events.HaLeaseLostEvent;
import et.elisa.stp.service.SbbServices;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * HA observation plane (DESIGN §4/§5): watches the active-standby lease and
 * the ASP-state consequences of lease loss. Observe-only — no sockets, no
 * timers of its own, no business logic; demotion itself is the RA's job
 * (Gate A, {@code RaHaSupport}/{@code SctpEndpointFailoverCoordinator}).
 *
 * <p>Active-active (default mode) never fires lease events — every node
 * serves, SLS redistribution handles node death (DESIGN §4 matrix).</p>
 */
public final class StpHaMonitorSbb implements Sbb, SleeEventHandler {
    private static final Logger LOG = LogManager.getLogger("SLEE");

    private final SbbServices services;

    public StpHaMonitorSbb() {
        this(null);
    }

    public StpHaMonitorSbb(SbbServices services) {
        this.services = services;
    }

    @Override public void sbbCreate() {}
    @Override public void sbbActivate() {}
    @Override public void sbbPassivate() {}
    @Override public void sbbRemove() {}

    @Override
    public void onEvent(SleeEvent event, ActivityContextInterface aci) {
        String detail;
        try {
            detail = observe(event);
        } catch (Throwable failure) {
            detail = "error=" + failure.getClass().getSimpleName() + ":" + failure.getMessage();
        }
        LOG.info("IN/OUT SBB=StpHaMonitorSbb event={} {}", type(event), detail);
    }

    /** Observation detail for one event — package-visible for unit tests. */
    String observe(SleeEvent event) {
        if (event == null) {
            return "null";
        }
        return switch (event) {
            case HaLeaseLostEvent e -> onHaLeaseLost(e);
            default -> "ignored";
        };
    }

    private String onHaLeaseLost(HaLeaseLostEvent e) {
        Object mode = svc().linkStatus() == null
                ? "?" : svc().linkStatus().snapshot().get("ha.mode");
        return "ha-lease-lost node=" + e.nodeId()
                + " generation=" + e.leaseGeneration()
                + " mode=" + mode;
    }

    private SbbServices svc() {
        return services == null ? SbbServices.get() : services;
    }

    private static String type(Object event) {
        return event == null ? "null" : event.getClass().getSimpleName();
    }
}
