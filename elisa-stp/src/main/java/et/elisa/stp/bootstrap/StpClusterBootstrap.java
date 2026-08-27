package et.elisa.stp.bootstrap;

import com.microjainslee.cluster.ClusterManager;
import com.microjainslee.core.MicroSleeConfiguration;
import com.microjainslee.core.MicroSleeContainer;

import io.quarkus.runtime.StartupEvent;

import jakarta.annotation.PreDestroy;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

/**
 * Cluster wiring BEFORE traffic (GMLC {@code GmlcClusterBootstrap} pattern,
 * DESIGN §5): binds the ISPN {@link ClusterManager} on the container so
 * ra-jss7 HA seams (leases / peer-route affinity / admin fabric) see one
 * fabric before any RA activates. {@code @Priority(20)} runs this ahead of
 * {@link StpBootstrap}.
 *
 * <p>DESIGN §9.5: the transit plane holds NO per-message state — this fabric
 * serves leases / admin / metrics only, never TCAP dialog state.</p>
 */
@ApplicationScoped
public class StpClusterBootstrap {
    private static final Logger LOG = LogManager.getLogger(StpClusterBootstrap.class);

    @Inject MicroSleeContainer container;

    @ConfigProperty(name = "stp.ha.node-id", defaultValue = "stp-node-1")
    String nodeIdProp;
    @ConfigProperty(name = "stp.cluster.enabled", defaultValue = "false")
    boolean clusterEnabledProp;
    @ConfigProperty(name = "stp.cluster.stack")
    Optional<String> clusterStackProp;
    @ConfigProperty(name = "stp.cluster.initial-hosts")
    Optional<String> clusterInitialHostsProp;

    private volatile ClusterManager cluster;

    /** Live fabric handle for RA seam wiring ({@code Ss7ApplyService}). Null after shutdown. */
    public ClusterManager clusterManager() {
        return cluster;
    }

    void onStart(@Observes @Priority(20) StartupEvent ev) {
        MicroSleeConfiguration.Builder builder = MicroSleeConfiguration.builder()
                .clusterEnabled(clusterEnabledProp)
                .nodeId(nodeIdProp);
        clusterStackProp.ifPresent(builder::clusterStack);
        clusterInitialHostsProp.ifPresent(builder::clusterInitialHosts);
        ClusterManager manager = new ClusterManager(builder.build(), nodeIdProp);
        manager.start();
        container.bindCluster(manager);
        cluster = manager;
        LOG.info("STP cluster fabric on Infinispan: node={} clusterMode={} "
                + "(leases/admin/metrics only — no per-message state, DESIGN §9.5)",
                manager.getNodeId(), manager.isClusterMode());
    }

    @PreDestroy
    void shutdown() {
        ClusterManager cm = cluster;
        if (cm != null) {
            cm.stop();
            cluster = null;
        }
    }
}
