package et.elisa.dra.app.bootstrap;

import com.microjainslee.cluster.ClusterManager;
import com.microjainslee.core.MicroSleeConfiguration;
import com.microjainslee.core.MicroSleeContainer;

import et.elisa.fabric.ElisaFabric;
import et.elisa.fabric.FabricRole;

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
 * DRA joins the IR.88 fabric BEFORE the relay plane activates
 * ({@code @Priority(20)} ahead of {DraBootstrapBean}). Mirrors
 * {@code StpClusterBootstrap}: one infinispan {@link ClusterManager} shared
 * across STP + IWF + DRA; DRA owns {@code peer-topology}/{@code route-policy}/
 * {@code th-map} (design §3).
 *
 * <p>Local test keeps {@code dra.cluster.enabled=false} → LOCAL caches, no
 * JGroups.</p>
 */
@ApplicationScoped
public class DraClusterBootstrap {
    private static final Logger LOG = LogManager.getLogger(DraClusterBootstrap.class);

    @Inject MicroSleeContainer container;

    @ConfigProperty(name = "dra.cluster.node-id", defaultValue = "dra-node-1")
    String nodeIdProp;
    @ConfigProperty(name = "dra.cluster.enabled", defaultValue = "false")
    boolean clusterEnabledProp;
    @ConfigProperty(name = "dra.cluster.stack", defaultValue = "tcp")
    Optional<String> clusterStackProp;
    @ConfigProperty(name = "dra.cluster.initial-hosts", defaultValue = "localhost[7800]")
    Optional<String> clusterInitialHostsProp;

    private volatile ClusterManager cluster;
    private volatile ElisaFabric fabric;

    public ClusterManager clusterManager() {
        return cluster;
    }

    public ElisaFabric fabric() {
        return fabric;
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
        fabric = new ElisaFabric(manager, FabricRole.DRA);
        LOG.info("DRA joined IR.88 fabric: node={} clusterMode={} fabricLocal={}",
                manager.getNodeId(), manager.isClusterMode(), !fabric.clustered());
    }

    @PreDestroy
    void shutdown() {
        ClusterManager cm = cluster;
        if (cm != null) {
            cm.stop();
            cluster = null;
            fabric = null;
        }
    }
}