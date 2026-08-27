package et.elisa.stp.service;

import et.elisa.stp.admin.AdminHttpHandler;
import et.elisa.stp.admin.LinkStatusService;

import com.microjainslee.api.RaCommandPort;
import com.microjainslee.core.MicroSleeContainer;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Constructor-injected collaborator bag for the STP SBBs (GMLC
 * {@code SbbServices} pattern). The transit plane is observe-only
 * (DESIGN §5), so the seams exposed here are status/config reads —
 * never transport handles.
 */
@ApplicationScoped
public class SbbServices {
    private static volatile SbbServices INSTANCE;

    @Inject MicroSleeContainer container;
    @Inject LinkStatusService linkStatus;
    @Inject StpTransitApplyService transit;
    @Inject AdminHttpHandler adminHttp;

    private volatile RaCommandPort http;

    public SbbServices() {}

    /** Explicit-collaborator constructor (unit tests / non-CDI harnesses). */
    public SbbServices(MicroSleeContainer container, LinkStatusService linkStatus,
                       StpTransitApplyService transit) {
        this.container = container;
        this.linkStatus = linkStatus;
        this.transit = transit;
    }

    @PostConstruct
    void install() { INSTANCE = this; }

    public static SbbServices get() {
        SbbServices s = INSTANCE;
        if (s == null) throw new IllegalStateException("SbbServices not initialized");
        return s;
    }

    public MicroSleeContainer container() { return container; }
    public LinkStatusService linkStatus() { return linkStatus; }
    public StpTransitApplyService transit() { return transit; }
    public AdminHttpHandler adminHttp() { return adminHttp; }

    /**
     * Re-bind the ra-http-server command port at each request (an
     * {@code @InjectRa} field is a one-time snapshot injected at SBB entity
     * creation) — mirrors GMLC {@code SbbServices.bindHttp}.
     */
    public void bindHttp(RaCommandPort http) { this.http = http; }

    public RaCommandPort http() { return http; }
}
