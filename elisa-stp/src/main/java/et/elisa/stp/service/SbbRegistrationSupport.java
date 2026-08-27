package et.elisa.stp.service;

import et.elisa.stp.events.AspStateChangeEvent;
import et.elisa.stp.events.HaLeaseLostEvent;
import et.elisa.stp.events.TransitRejectedEvent;
import et.elisa.stp.sbb.HttpServerSbb;
import et.elisa.stp.sbb.StpHaMonitorSbb;
import et.elisa.stp.sbb.StpOamSbb;

import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.ra.httpserver.events.HttpWebRequestEvent;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * SBB type registration + event mapping for the STP observe plane
 * (DESIGN §5). Mirrors the GMLC {@code SbbRegistrationSupport}; the
 * transit data plane stays in-stack (jSS7 MTP transit + GTT fast path).
 */
@ApplicationScoped
public class SbbRegistrationSupport {
    @Inject MicroSleeContainer container;
    @Inject SbbServices sbbServices;

    public SbbRegistrationSupport() {}

    /** Explicit-collaborator constructor (unit tests / non-CDI harnesses). */
    public SbbRegistrationSupport(MicroSleeContainer container, SbbServices sbbServices) {
        this.container = container;
        this.sbbServices = sbbServices;
    }

    public void unregisterAll() {
        for (String n : new String[]{"StpOamSbb", "StpHaMonitorSbb", "HttpServerSbb"}) {
            container.getSbbTypeRegistry().unregisterByName(n);
        }
    }

    public void registerAll() {
        container.registerSbbType(StpOamSbb.class, () -> new StpOamSbb(sbbServices));
        container.registerSbbType(StpHaMonitorSbb.class, () -> new StpHaMonitorSbb(sbbServices));
        container.registerSbbType(HttpServerSbb.class, () -> new HttpServerSbb(sbbServices));
    }

    public void bindEventMappings() {
        container.mapEventToSbb(AspStateChangeEvent.class, "StpOamSbb");
        container.mapEventToSbb(TransitRejectedEvent.class, "StpOamSbb");
        container.mapEventToSbb(HaLeaseLostEvent.class, "StpHaMonitorSbb");
        container.mapEventToSbb(HttpWebRequestEvent.class, "HttpServerSbb");
    }
}
