package et.elisa.iwf.service;

import et.elisa.iwf.admin.LinkStatusService;
import et.elisa.iwf.bootstrap.IwfBootstrap;
import et.elisa.iwf.mapping.DialogBindingRegistry;
import et.elisa.iwf.mapping.Ts29305Table;
import et.elisa.iwf.telemetry.AppTelemetry;
import et.elisa.iwf.telemetry.IwfKpi;

import com.microjainslee.core.MicroSleeContainer;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Service locator — single access point for SBBs and non-CDI components.
 * Mirrors the GMLC {@code SbbServices} pattern.
 */
@ApplicationScoped
public class IwfSbbServices {

    private static volatile IwfSbbServices INSTANCE;

    @Inject MicroSleeContainer container;
    @Inject IwfBootstrap bootstrap;
    @Inject AppTelemetry appTelemetry;
    @Inject LinkStatusService linkStatus;
    @Inject Ss7ApplyService ss7Apply;

    @PostConstruct
    void install() {
        INSTANCE = this;
    }

    public static IwfSbbServices get() {
        IwfSbbServices s = INSTANCE;
        if (s == null) {
            throw new IllegalStateException("IwfSbbServices not initialized");
        }
        return s;
    }

    public MicroSleeContainer container() {
        return container;
    }

    public IwfBootstrap bootstrap() {
        return bootstrap;
    }

    public AppTelemetry appTelemetry() {
        return appTelemetry;
    }

    public LinkStatusService linkStatus() {
        return linkStatus;
    }

    public Ss7ApplyService ss7Apply() {
        return ss7Apply;
    }

    public DialogBindingRegistry bindings() {
        return bootstrap.bindingRegistry();
    }
}
