package et.elisa.stp.bootstrap;

import et.elisa.stp.admin.AdminHttpHandler;
import et.elisa.stp.admin.LinkStatusService;
import et.elisa.stp.service.HttpApplyService;
import et.elisa.stp.service.SbbRegistrationSupport;
import et.elisa.stp.service.Ss7ApplyService;
import et.elisa.stp.service.StpTransitApplyService;
import et.elisa.stp.telemetry.AppTelemetry;

import com.microjainslee.core.MicroSleeContainer;

import io.quarkus.runtime.StartupEvent;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Nextgen STP boot sequence (GMLC {@code GmlcBootstrap} ordering, DESIGN §5):
 * telemetry → SBB types → HTTP RA → SS7 RA → Monitor Hub → IES dispatcher →
 * event mappings. The transit data plane stays in-stack; this bootstrap only
 * wires the observe/control plane.
 */
@ApplicationScoped
public class StpBootstrap {
    private static final Logger LOG = LogManager.getLogger(StpBootstrap.class);

    @Inject MicroSleeContainer container;
    @Inject SbbRegistrationSupport sbbRegistration;
    @Inject AppTelemetry appTelemetry;
    @Inject LinkStatusService linkStatus;
    @Inject Ss7ApplyService ss7Apply;
    @Inject HttpApplyService httpApply;
    @Inject AdminHttpHandler adminHttp;
    @Inject StpTransitApplyService transit;

    @ConfigProperty(name = "stp.boot.fail-on-wire-error", defaultValue = "false")
    boolean failOnWireError;

    void onStart(@Observes StartupEvent ev) {
        httpApply.tearDown();
        ss7Apply.tearDown();
        sbbRegistration.unregisterAll();
        if (container.getState() != MicroSleeContainer.State.STARTED) {
            container.start();
        }
        appTelemetry.install(container);
        linkStatus.clearSs7();
        linkStatus.clearHttp();
        sbbRegistration.registerAll();
        httpApply.wire();
        if (ss7Apply.enabled()) {
            try {
                LOG.info("SS7 boot: {}", ss7Apply.wireIfConfigured());
            } catch (RuntimeException ex) {
                if (failOnWireError) {
                    throw new IllegalStateException(
                            "SS7 wire failed and stp.boot.fail-on-wire-error=true: "
                                    + ex.getMessage(), ex);
                }
                LOG.warn("SS7 boot wire failed (lab may run without M3UA): {}", ex.getMessage());
            }
        }
        adminHttp.wireRaAdminHub();
        container.createIesDispatcher();
        sbbRegistration.bindEventMappings();
        LOG.info("STP bootstrap complete (SS7 transit only; transit-enabled={})",
                transit.transitEnabled());
    }

    @PreDestroy
    void shutdown() {
        adminHttp.clearRaAdminHub();
        appTelemetry.close();
        httpApply.tearDown();
        ss7Apply.tearDown();
    }
}
