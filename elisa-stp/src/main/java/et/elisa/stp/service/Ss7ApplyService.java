package et.elisa.stp.service;

import et.elisa.stp.admin.LinkStatusService;
import et.elisa.stp.bootstrap.StpClusterBootstrap;
import et.elisa.stp.config.RuntimeConfigStore;
import et.elisa.stp.config.SctpJsonStamp;
import et.elisa.stp.telemetry.StpKpi;

import com.microjainslee.cluster.ClusterManager;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.ra.jss7.Ss7RaConfig;
import com.microjainslee.ra.jss7.Ss7RaEndpoint;
import com.microjainslee.ra.jss7.Ss7ResourceAdaptor;
import com.microjainslee.ra.jss7.admin.Ss7AdminBindings;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.restcomm.protocols.ss7.config.Ss7Config;
import org.restcomm.protocols.ss7.config.Ss7ConfigLoader;

/**
 * Wires ra-jss7 exactly like the GMLC {@code Ss7ApplyService}, but never
 * installs MAP/CAP listeners — the STP plane stays pure-relay.
 */
@ApplicationScoped
public class Ss7ApplyService {
    private static final Logger LOG = LogManager.getLogger(Ss7ApplyService.class);

    @Inject MicroSleeContainer container;
    @Inject LinkStatusService linkStatus;
    @Inject RuntimeConfigStore store;
    @Inject StpTransitApplyService transit;
    @Inject StpClusterBootstrap clusterBootstrap;

    @ConfigProperty(name = "stp.ss7.enabled", defaultValue = "true")
    boolean ss7EnabledProp;
    @ConfigProperty(name = "stp.ss7.config-file")
    Optional<String> ss7ConfigFileProp;
    @ConfigProperty(name = "stp.ss7.host-ip", defaultValue = "127.0.0.1")
    String hostIpProp;
    @ConfigProperty(name = "stp.ss7.host-port", defaultValue = "8011")
    int hostPortProp;
    @ConfigProperty(name = "stp.ss7.peer-ip", defaultValue = "127.0.0.1")
    String peerIpProp;
    @ConfigProperty(name = "stp.ss7.peer-port", defaultValue = "8012")
    int peerPortProp;
    @ConfigProperty(name = "stp.ss7.opc", defaultValue = "10")
    int opcProp;
    @ConfigProperty(name = "stp.ss7.dpc", defaultValue = "20")
    int dpcProp;
    @ConfigProperty(name = "stp.ss7.ip-channel-type", defaultValue = "SCTP")
    String channelProp;
    @ConfigProperty(name = "sctp.backend", defaultValue = "FSTACK_DPDK")
    String sctpBackendProp;
    @ConfigProperty(name = "sctp.fstack.mode", defaultValue = "IN_PROCESS")
    String sctpModeProp;
    @ConfigProperty(name = "sctp.fstack.dataplane", defaultValue = "LOOPBACK")
    String sctpDataplaneProp;
    @ConfigProperty(name = "sctp.fstack.inprocess.enabled", defaultValue = "true")
    boolean sctpInProcessProp;
    @ConfigProperty(name = "sctp.fstack.library", defaultValue = "lib/libsctp_fstack.so")
    String sctpLibraryProp;

    private volatile Ss7RaEndpoint ss7Endpoint;
    private volatile Ss7ResourceAdaptor ss7Ra;

    public boolean enabled() { return ss7EnabledProp; }

    public String apply() { return tearDown() + ";" + wireIfConfigured(); }
    public String stop() { linkStatus.markSs7Stopped(); return tearDown(); }
    public String start() { return ss7Endpoint != null ? apply() : wireIfConfigured(); }

    public String tearDown() {
        linkStatus.clearSs7();
        if (ss7Endpoint == null) return "ss7-drained=noop";
        try {
            ss7Endpoint.deactivate();
            return "ss7-drained=ok";
        } catch (RuntimeException re) {
            LOG.warn("ra-jss7 deactivate: {}", re.getMessage());
            return "ss7-drained=error:" + re.getMessage();
        } finally {
            ss7Endpoint = null;
            ss7Ra = null;
            Ss7AdminBindings.clear();
        }
    }

    public String wireIfConfigured() {
        if (!enabled()) {
            LOG.info("stp.ss7.enabled=false — SS7 plane off");
            return "ss7=off";
        }
        Ss7Config full = resolveSs7Config();
        if (full == null && !store.anyPresent(RuntimeConfigStore.Keys.ss7())) {
            LOG.info("ra-jss7 not wired: no ss7.json or stp.ss7.* override");
            return "ss7=no-config";
        }
        wireTelemetryHook();
        try {
            if (full != null) {
                stampSctpSystemProperties(full);
            }
            wireSs7Ra(full);
            if (!ss7Ra.isActive()) {
                throw new IllegalStateException("ra-jss7 registered but not active");
            }
            String detail = "ss7=wired;transit=" + transit.transitEnabled();
            linkStatus.setSs7AppliedDetail(detail);
            return detail;
        } catch (RuntimeException ex) {
            linkStatus.setSs7AppliedDetail("ss7=error:" + ex.getMessage());
            throw ex;
        }
    }

    private void wireSs7Ra(Ss7Config full) {
        Ss7ResourceAdaptor ra = new Ss7ResourceAdaptor();
        // HA seams (mirror container.bindRaHaSeams, which the hot registerRa path
        // skips): container checkpoint + ISPN fabric → dialog ownership tracker,
        // sticky outbound router and SCTP endpoint failover coordinator all arm.
        ra.setMicroSleeContainer(container);
        ClusterManager cm = clusterBootstrap == null ? null : clusterBootstrap.clusterManager();
        if (cm != null) {
            ra.setClusterManager(cm);
        }
        if (full != null) {
            ra.setSs7Config(full);
        } else {
            Ss7RaConfig cfg = new Ss7RaConfig()
                    .hostIp(store.getOr(RuntimeConfigStore.Keys.SS7_HOST_IP, hostIpProp))
                    .hostPort(store.getInt(RuntimeConfigStore.Keys.SS7_HOST_PORT, hostPortProp))
                    .peerIp(store.getOr(RuntimeConfigStore.Keys.SS7_PEER_IP, peerIpProp))
                    .peerPort(store.getInt(RuntimeConfigStore.Keys.SS7_PEER_PORT, peerPortProp))
                    .originatingPointCode(store.getInt(RuntimeConfigStore.Keys.SS7_OPC, opcProp))
                    .destinationPointCode(store.getInt(RuntimeConfigStore.Keys.SS7_DPC, dpcProp))
                    // STP: no MAP/CAP listeners — relay only.
                    .mapEnabled(false).capEnabled(false)
                    .ipChannelType(store.getOr(RuntimeConfigStore.Keys.SS7_CHANNEL, channelProp));
            ra.setConfig(cfg);
        }
        // Transit-plane posture (canRelay / removeSpc / incoming ACL / HA mode)
        // is applied at raActive() by the RA; it must be set BEFORE registerRa.
        transit.applyToRa(ra);
        ss7Ra = ra;
        ss7Endpoint = new Ss7RaEndpoint(ra);
        container.registerRa(ss7Endpoint, ss7Endpoint);
        if (!ra.isActive()) {
            ss7Endpoint = null;
            ss7Ra = null;
            linkStatus.clearSs7();
            Ss7AdminBindings.clear();
            throw new IllegalStateException("ra-jss7 activate failed (SCTP/M3UA)");
        }
        linkStatus.bindSs7(ra);
        Ss7AdminBindings.bind(ss7Endpoint);
    }

    /**
     * JSON {@code sctp.*} wins; MicroProfile / {@code -D} only fill omitted fields.
     * F-Stack Management reads System properties, not MicroProfile config.
     */
    void stampSctpSystemProperties(Ss7Config cfg) {
        SctpJsonStamp.overwriteFromJson(cfg);
        SctpJsonStamp.fallbackIfAbsent(
                sctpBackendProp, sctpModeProp, sctpDataplaneProp, sctpInProcessProp, sctpLibraryProp);
        LOG.info("SCTP runtime backend={} mode={} dataplane={} inprocess={} library={}",
                System.getProperty("sctp.backend"),
                System.getProperty("sctp.fstack.mode"),
                System.getProperty("sctp.fstack.dataplane"),
                System.getProperty("sctp.fstack.inprocess.enabled"),
                System.getProperty("sctp.fstack.library"));
    }

    private Ss7Config resolveSs7Config() {
        try {
            String cfgFile = ss7ConfigFileProp.orElse(null);
            if (cfgFile != null && !cfgFile.isBlank()) {
                Path p = Path.of(cfgFile);
                if (Files.isRegularFile(p)) {
                    LOG.info("SS7 stack JSON {}", p.toAbsolutePath());
                    return Ss7ConfigLoader.load(p);
                }
                LOG.warn("stp.ss7.config-file not found: {}", cfgFile);
            }
        } catch (RuntimeException ex) {
            LOG.error("Ss7Config load failed: {}", ex.getMessage());
        }
        return null;
    }

    /**
     * Tears down the current stack and re-wires ra-jss7 from an explicit
     * merged SS7 document (the admin merge-and-apply path). Keeps the boot
     * path ({@link #wireIfConfigured}) unchanged so startup stays on-demand.
     */
    public String applyFile(Path file) {
        String drained = tearDown();
        if (file == null || !Files.isRegularFile(file)) {
            return drained + ";ss7=no-config";
        }
        try {
            Ss7Config full = Ss7ConfigLoader.load(file);
            stampSctpSystemProperties(full);
            wireSs7Ra(full);
            if (!ss7Ra.isActive()) {
                throw new IllegalStateException("ra-jss7 registered but not active");
            }
            String detail = "ss7=wired;source=" + file.getFileName();
            linkStatus.setSs7AppliedDetail(detail);
            return drained + ";" + detail;
        } catch (RuntimeException ex) {
            linkStatus.setSs7AppliedDetail("ss7=error:" + ex.getMessage());
            LOG.error("applyFile({}) failed: {}", file, ex.getMessage());
            throw ex;
        }
    }

    public Ss7RaEndpoint endpoint() { return ss7Endpoint; }

    /**
     * Bridge the jSS7 static telemetry seam into {@link StpKpi} so relay / GTT /
     * ACL counters mirror live transit traffic into {@code /metrics} and the
     * Monitor Hub. Static hook = one JVM one stack (this product line); safe to
     * re-set on every apply.
     */
    private void wireTelemetryHook() {
        try {
            org.restcomm.protocols.ss7.sccp.impl.SccpTelemetryHook.set(
                    new org.restcomm.protocols.ss7.sccp.impl.SccpTelemetryHook.Listener() {
                        @Override public void onRelayed(int incomingOpc, int dpc) {
                            StpKpi.relayForwarded();
                        }
                        @Override public void onGttTranslated(int incomingOpc, int dpc) {
                            StpKpi.gtTranslated();
                        }
                        @Override public void onGttUnrouted(int incomingOpc) {
                            StpKpi.gtUnrouted();
                        }
                        @Override public void onAclDenied(int incomingOpc) {
                            StpKpi.aclDenied(incomingOpc);
                        }
                    });
            LOG.info("SccpTelemetryHook wired → StpKpi (relay/gtt/acl counters live)");
        } catch (RuntimeException ex) {
            LOG.warn("SccpTelemetryHook unavailable: {}", ex.toString());
        }
    }
}
