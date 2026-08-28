package et.elisa.iwf.bootstrap;

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
 * IWF joins the IR.88 fabric before accepting traffic ({@code @Priority(20)}
 * ahead of {@link IwfBootstrap}). IWF owns {@code imsi-context}/
 * {@code dialog-bind} anchoring caches (design §3/§4.1); it READS
 * {@code route-policy}/{@code th-map}/{@code peer-state} for MAP↔Diameter
 * translation without REST queries (design §4).
 */
@ApplicationScoped
public class IwfClusterBootstrap {
    private static final Logger LOG = LogManager.getLogger(IwfClusterBootstrap.class);

    @Inject MicroSleeContainer container;

    @ConfigProperty(name = "iwf.cluster.node-id", defaultValue = "iwf-node-1")
    String nodeIdProp;
    @ConfigProperty(name = "iwf.cluster.enabled", defaultValue = "false")
    boolean clusterEnabledProp;
    @ConfigProperty(name = "iwf.cluster.stack", defaultValue = "tcp")
    Optional<String> clusterStackProp;
    @ConfigProperty(name = "iwf.cluster.initial-hosts", defaultValue = "localhost[7800]")
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
        fabric = new ElisaFabric(manager, FabricRole.IWF);
        LOG.info("IWF joined IR.88 fabric: node={} clusterMode={} fabricLocal={}",
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