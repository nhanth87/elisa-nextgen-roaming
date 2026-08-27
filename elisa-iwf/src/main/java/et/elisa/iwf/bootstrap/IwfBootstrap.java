package et.elisa.iwf.bootstrap;

import et.elisa.iwf.IwfConfig;
import et.elisa.iwf.IwfConfigJson;
import et.elisa.iwf.admin.LinkStatusService;
import et.elisa.iwf.diameter.CorsacDiameterLeg;
import et.elisa.iwf.diameter.DiameterLeg;
import et.elisa.iwf.map.MapDialogHandler;
import et.elisa.iwf.map.NoopMapLeg;
import et.elisa.iwf.mapping.DialogBindingRegistry;
import et.elisa.iwf.mapping.Ts29305Table;
import et.elisa.iwf.service.Ss7ApplyService;
import et.elisa.iwf.telemetry.AppTelemetry;

import com.microjainslee.core.MicroSleeContainer;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * IWF bootstrap: loads config, builds both legs, wires the mapping engine,
 * installs telemetry, and manages link status. Diameter leg is a real
 * corsac client (M-IWF-2); MAP leg lands in M-IWF-3.
 */
@ApplicationScoped
public class IwfBootstrap {

    private static final Logger LOG = LogManager.getLogger(IwfBootstrap.class);

    @Inject
    MicroSleeContainer container;

    @Inject
    AppTelemetry appTelemetry;

    @Inject
    LinkStatusService linkStatus;

    @Inject
    Ss7ApplyService ss7Apply;

    private volatile IwfConfig config;
    private volatile CorsacDiameterLeg diameterLeg;
    private volatile MapDialogHandler mapLeg;
    private final DialogBindingRegistry bindingRegistry = new DialogBindingRegistry();

    void onStart(@Observes StartupEvent ev) {
        config = IwfConfigJson.load();

        // Start container if not already started
        if (container.getState() != MicroSleeContainer.State.STARTED) {
            container.start();
        }

        // Install telemetry
        appTelemetry.install(container);

        // Diameter leg
        diameterLeg = new CorsacDiameterLeg(config.diameter());
        diameterLeg.setBindingRegistry(bindingRegistry);
        diameterLeg.start();
        linkStatus.setDiaApplied("dia=wired;source=config");

        // MAP leg (skeleton)
        mapLeg = new NoopMapLeg();
        mapLeg.setInboundListener((op, dialogId, args) ->
                LOG.info("[iwf] inbound MAP {} dialog={} -> engine dispatch (skeleton log only)",
                        op, dialogId));

        // SS7/MAP RA (skeleton — M-IWF-3 will wire real ra-jss7)
        try {
            ss7Apply.wireIfConfigured();
        } catch (RuntimeException ex) {
            LOG.warn("SS7 boot wire failed (lab may run without MAP): {}", ex.getMessage());
        }

        LOG.info("[iwf] bootstrap complete: mappings={} diaTarget={}:{} srcPort={} "
                        + "originHost={} originRealm={} destHost={} destRealm={} "
                        + "mapSsn={} mapGt={} mapSpc={} "
                        + "(MAP leg skeleton until M-IWF-3)",
                Ts29305Table.all().size(),
                config.diameter().draHost(), config.diameter().draPort(),
                config.diameter().srcPort(),
                config.diameter().originHost(), config.diameter().originRealm(),
                config.diameter().destHost(), config.diameter().destRealm(),
                config.map().ssn(), config.map().ownGt(), config.map().ownSpc());
    }

    void onStop(@Observes ShutdownEvent ev) {
        shutdown();
    }

    @PreDestroy
    void shutdown() {
        CorsacDiameterLeg leg = diameterLeg;
        if (leg != null) {
            leg.stop();
            linkStatus.clearDia();
        }
        ss7Apply.tearDown();
        appTelemetry.close();
        LOG.info("[iwf] shutdown complete");
    }

    public DiameterLeg diameterLeg() {
        return diameterLeg;
    }

    public MapDialogHandler mapLeg() {
        return mapLeg;
    }

    public IwfConfig config() {
        return config;
    }

    public DialogBindingRegistry bindingRegistry() {
        return bindingRegistry;
    }
}
