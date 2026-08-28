package et.elisa.dra.lab.sgsn;

import com.microjainslee.ra.jss7.Ss7RaConfig;
import com.microjainslee.ra.jss7.transport.Ss7Stack;

import org.restcomm.protocols.ss7.indicator.RoutingIndicator;
import org.restcomm.protocols.ss7.map.api.MAPParameterFactory;
import org.restcomm.protocols.ss7.map.api.MAPProvider;
import org.restcomm.protocols.ss7.sccp.SccpProvider;
import org.restcomm.protocols.ss7.sccp.parameter.GlobalTitle;
import org.restcomm.protocols.ss7.sccp.parameter.ParameterFactory;
import org.restcomm.protocols.ss7.sccp.parameter.SccpAddress;

/**
 * Wraps {@link Ss7Stack} as a plain Java object (no SLEE container).
 *
 * <p>One per lab JVM. Threading model: jSS7's MAP/SCCP run on their own
 * delivery thread pool. Callers (the SGSN-sim dialog manager + test
 * methods) must not block the delivery thread — long work goes on
 * a virtual thread ({@code Thread.ofVirtual().start(...)}) per the
 * {@code et.elisa.roaming} AGENTS rule "no blocking IO on SLEE event
 * thread" (same rule applies to the jSS7 MAP delivery thread).</p>
 *
 * <p>Build against the restructured ra-jss7 stack naming
 * ({@code org.restcomm.protocols.ss7.*} 9.2.8-j25).</p>
 */
public final class SgsnSimStack implements AutoCloseable {

    private final SgsnSimConfig cfg;
    private final Ss7RaConfig raConfig;
    private final Ss7Stack stack;
    private final ParameterFactory sccpFactory;

    public SgsnSimStack(SgsnSimConfig cfg) throws Exception {
        this.cfg = cfg;
        this.raConfig = buildRaConfig(cfg);
        this.stack = new Ss7Stack(raConfig);
        this.stack.start();
        this.sccpFactory = this.stack.sccpProvider().getParameterFactory();
    }

    public SgsnSimConfig config() {
        return cfg;
    }

    public MAPProvider mapProvider() {
        return stack.mapProvider();
    }

    public MAPParameterFactory mapParameterFactory() {
        return mapProvider().getMAPParameterFactory();
    }

    public SccpProvider sccpProvider() {
        return stack.sccpProvider();
    }

    public ParameterFactory sccpParameterFactory() {
        return sccpFactory;
    }

    public boolean isSctpAssociationUp() {
        return stack.isSctpAssociationUp();
    }

    public boolean isM3uaAsActive() {
        return stack.isM3uaAsActive();
    }

    /**
     * Local SccpAddress — point-code + local SSN, used as calling-party
     * for outbound MAP dialogs.
     */
    public SccpAddress localSccpAddress() {
        return sccpFactory.createSccpAddress(
                RoutingIndicator.ROUTING_BASED_ON_DPC_AND_SSN,
                sccpFactory.createGlobalTitle("", 0),
                cfg.originatingPointCode(), cfg.localSsn());
    }

    /**
     * Remote SccpAddress — DPC + remote SSN (global-title empty). Used when
     * the called party is reached via point-code translation.
     */
    public SccpAddress remotePcSccpAddress() {
        return sccpFactory.createSccpAddress(
                RoutingIndicator.ROUTING_BASED_ON_DPC_AND_SSN,
                sccpFactory.createGlobalTitle("", 0),
                cfg.destinationPointCode(), cfg.remoteSsn());
    }

    /**
     * Remote SccpAddress — global-title routing to the IWF's external GT.
     * The STP GTT rewrites {@code cfg.iwfGt()} → internal PC=250 GTT.
     */
    public SccpAddress iwfSccpAddress() {
        GlobalTitle gt = sccpFactory.createGlobalTitle(cfg.iwfGt(), 0);
        return sccpFactory.createSccpAddress(
                RoutingIndicator.ROUTING_BASED_ON_GLOBAL_TITLE,
                gt,
                cfg.destinationPointCode(), cfg.remoteSsn());
    }

    private static Ss7RaConfig buildRaConfig(SgsnSimConfig cfg) {
        return new Ss7RaConfig()
                .stackName(cfg.stackName())
                .hostIp(cfg.hostIp())
                .hostPort(cfg.hostPort())
                .peerIp(cfg.peerIp())
                .peerPort(cfg.peerPort())
                .associationName(cfg.stackName() + "-to-stp")
                .ipChannelType("SCTP")
                .sctpWorkerThreads(2)
                .routingContext(cfg.routingContext())
                .networkIndicator(cfg.networkIndicator())
                .originatingPointCode(cfg.originatingPointCode())
                .destinationPointCode(cfg.destinationPointCode())
                .serviceIndicator(3)
                .ipspClient(true)
                .localSsn(cfg.localSsn())
                .remoteSsn(cfg.remoteSsn())
                .dialogIdleTimeoutMs(cfg.dialogIdleTimeoutMs())
                .invokeTimeoutMs(cfg.invokeTimeoutMs())
                .maxDialogs(500)
                .dialogIdRangeStart(0L)
                .dialogIdRangeEnd(0L)
                .mapEnabled(true)
                .capEnabled(false)
                .congestionControlBlockingOutgoingSccpMessages(false)
                .defaultRestrictionLevel(0)
                .defaultTrafficMode(Ss7RaConfig.TRAFFIC_MODE_LOADSHARE);
    }

    @Override
    public void close() {
        try {
            stack.stop();
        } catch (Exception e) {
            throw new RuntimeException("SgsnSimStack.stop failed", e);
        }
    }
}
