package et.elisa.iwf.telemetry;

import et.elisa.iwf.bootstrap.IwfBootstrap;

import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.telemetry.MicrometerTelemetryPort;
import com.microjainslee.telemetry.TelemetryDispatchObserver;
import com.microjainslee.telemetry.TelemetryPort;
import com.microjainslee.telemetry.TelemetryRaObserver;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@ApplicationScoped
public class AppTelemetry {

    private static final Logger LOG = LogManager.getLogger(AppTelemetry.class);

    @Inject
    IwfBootstrap bootstrap;

    volatile TelemetryPort port;

    public TelemetryPort install(MicroSleeContainer container) {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        MicrometerTelemetryPort tp = new MicrometerTelemetryPort(registry, container);
        tp.start();
        container.getEventRouter().setDispatchObserver(new TelemetryDispatchObserver(tp));
        container.setRaObserver(new TelemetryRaObserver(tp));
        var bindings = bootstrap == null ? null : bootstrap.bindingRegistry();
        tp.customGauge("iwf_binding_size",
                () -> bindings == null ? 0 : bindings.size());
        IwfKpi.bindPort(tp);
        this.port = tp;
        LOG.info("IWF telemetry armed");
        return tp;
    }

    public TelemetryPort port() {
        return port;
    }

    /** Extra keys for the admin monitor-feed live tiles. */
    public Map<String, Object> feedExtras() {
        var bindings = bootstrap == null ? null : bootstrap.bindingRegistry();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("iwf.binding.size", bindings == null ? 0 : bindings.size());
        m.put("kpi.map.request", IwfKpi.value("map.request"));
        m.put("kpi.map.response.success", IwfKpi.value("map.response.success"));
        m.put("kpi.map.response.fail", IwfKpi.value("map.response.fail"));
        m.put("kpi.dia.request", IwfKpi.value("dia.request"));
        m.put("kpi.dia.response.success", IwfKpi.value("dia.response.success"));
        m.put("kpi.dia.response.fail", IwfKpi.value("dia.response.fail"));
        return m;
    }

    public void close() {
        TelemetryPort tp = port;
        port = null;
        IwfKpi.bindPort(null);
        if (tp instanceof MicrometerTelemetryPort micrometer) {
            try {
                micrometer.stop();
            } catch (RuntimeException e) {
                LOG.debug("telemetry stop: {}", e.toString());
            }
        }
    }
}
