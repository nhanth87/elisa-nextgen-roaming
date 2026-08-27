package et.elisa.stp.service;

import et.elisa.stp.admin.LinkStatusService;
import et.elisa.stp.config.RuntimeConfigStore;

import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.ra.httpserver.HttpServerRaEndpoint;
import com.microjainslee.ra.httpserver.HttpServerResourceAdaptor;
import com.microjainslee.ra.httpserver.admin.HttpServerAdminBindings;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Wires the ra-http-server RA so the admin dashboard, Monitor Hub and
 * {@code /metrics} surface can serve HTTP (mirrors the GMLC
 * {@code HttpApplyService}, server-only — the STP has no outbound HTTP client).
 */
@ApplicationScoped
public class HttpApplyService {
    private static final Logger LOG = LogManager.getLogger(HttpApplyService.class);

    @Inject MicroSleeContainer container;
    @Inject LinkStatusService linkStatus;
    @Inject RuntimeConfigStore store;

    @ConfigProperty(name = "http.ra.port", defaultValue = "8090")
    int httpRaPortProp;
    @ConfigProperty(name = "http.ra.host", defaultValue = "0.0.0.0")
    String httpRaHostProp;
    @ConfigProperty(name = "http.ra.event-loop-threads", defaultValue = "8")
    int httpRaEventLoopProp;
    @ConfigProperty(name = "http.ra.worker-pool-size", defaultValue = "256")
    int httpRaWorkerPoolProp;
    @ConfigProperty(name = "http.ra.accept-backlog", defaultValue = "8192")
    int httpRaAcceptBacklogProp;

    private volatile HttpServerRaEndpoint serverEndpoint;

    public String apply() {
        return tearDown() + ";" + wire();
    }

    public String tearDown() {
        HttpServerAdminBindings.clear();
        String serverMsg = "http-server-drained=noop";
        if (serverEndpoint != null) {
            try {
                serverEndpoint.deactivate();
                serverMsg = "http-server-drained=ok";
            } catch (RuntimeException e) {
                serverMsg = "http-server-drained=warn";
            } finally {
                serverEndpoint = null;
            }
        }
        linkStatus.clearHttp();
        return serverMsg;
    }

    public String wire() {
        int port = store.getInt(RuntimeConfigStore.Keys.HTTP_RA_PORT, httpRaPortProp);
        String host = store.getOr(RuntimeConfigStore.Keys.HTTP_RA_HOST, httpRaHostProp);
        HttpServerResourceAdaptor ra = new HttpServerResourceAdaptor();
        ra.setPort(port);
        ra.setHost(host);
        invokeIntSetter(ra, "setEventLoopThreads",
                store.getInt(RuntimeConfigStore.Keys.HTTP_RA_EVENT_LOOP, httpRaEventLoopProp));
        invokeIntSetter(ra, "setWorkerPoolSize",
                store.getInt(RuntimeConfigStore.Keys.HTTP_RA_WORKER_POOL, httpRaWorkerPoolProp));
        invokeIntSetter(ra, "setAcceptBacklog",
                store.getInt(RuntimeConfigStore.Keys.HTTP_RA_ACCEPT_BACKLOG, httpRaAcceptBacklogProp));
        serverEndpoint = new HttpServerRaEndpoint(ra);
        serverEndpoint.setPort(port);
        // HA seam (hot registerRa path skips bindRaHaSeams): the checkpoint
        // bridge needs the container reference before this RA activates.
        ra.setMicroSleeContainer(container);
        container.registerRa(serverEndpoint, serverEndpoint);
        HttpServerAdminBindings.bind(serverEndpoint);
        linkStatus.markHttpListen(port);
        String detail = "http-server=wired;listen=" + host + ":" + port;
        linkStatus.setHttpDetail(detail);
        LOG.info("HTTP apply: {}", detail);
        return detail;
    }

    public HttpServerRaEndpoint endpoint() {
        return serverEndpoint;
    }

    private static void invokeIntSetter(Object target, String method, int value) {
        try {
            target.getClass().getMethod(method, int.class).invoke(target, value);
        } catch (ReflectiveOperationException ex) {
            LOG.debug("HTTP RA {} unavailable: {}", method, ex.toString());
        }
    }
}