package et.elisa.stp.sbb;

import et.elisa.stp.events.AspStateChangeEvent;
import et.elisa.stp.events.TransitRejectedEvent;
import et.elisa.stp.service.SbbServices;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * OAM observation plane (DESIGN §5 SBB table): ASP state transitions, ACL
 * rejects and congestion drops surfaced as {@link TransitRejectedEvent}.
 *
 * <p>Observe-only by the golden rule: this SBB never opens sockets and
 * carries no business logic — the relay stays in-stack (jSS7 MTP transit +
 * GTT fast path); the SBB only records what the RA plane reports.</p>
 *
 * <p>The ra-jss7 command port is owned by {@code Ss7ApplyService}; this SBB
 * does <em>not</em> re-bind on each event — an {@code @InjectRa} field is a
 * one-time snapshot injected at entity creation (GMLC G4 lesson).</p>
 */
public final class StpOamSbb implements Sbb, SleeEventHandler {
    private static final Logger LOG = LogManager.getLogger("SLEE");

    private final SbbServices services;

    public StpOamSbb() {
        this(null);
    }

    public StpOamSbb(SbbServices services) {
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
        LOG.info("IN/OUT SBB=StpOamSbb event={} {}", type(event), detail);
    }

    /** Observation detail for one event — package-visible for unit tests. */
    String observe(SleeEvent event) {
        if (event == null) {
            return "null";
        }
        return switch (event) {
            case AspStateChangeEvent e -> onAspStateChange(e);
            case TransitRejectedEvent e -> onTransitRejected(e);
            default -> "ignored";
        };
    }

    private String onAspStateChange(AspStateChangeEvent e) {
        boolean routeReady = svc().linkStatus() != null && svc().linkStatus().isM3uaRouteReady();
        return "asp-state asp=" + e.aspName()
                + " " + (e.previousState() == null ? "-" : e.previousState())
                + "->" + e.currentState()
                + " routeReady=" + routeReady;
    }

    private String onTransitRejected(TransitRejectedEvent e) {
        return "transit-reject opc=" + e.opc()
                + " reason=" + e.reason()
                + (e.detail() == null || e.detail().isBlank() ? "" : " detail=" + e.detail());
    }

    private SbbServices svc() {
        return services == null ? SbbServices.get() : services;
    }

    private static String type(Object event) {
        return event == null ? "null" : event.getClass().getSimpleName();
    }
}
