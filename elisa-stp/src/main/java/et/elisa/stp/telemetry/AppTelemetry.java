package et.elisa.stp.telemetry;

import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.telemetry.MicrometerTelemetryPort;
import com.microjainslee.telemetry.TelemetryDispatchObserver;
import com.microjainslee.telemetry.TelemetryPort;
import com.microjainslee.telemetry.TelemetryRaObserver;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * STP telemetry arming (mirrors the GMLC {@code AppTelemetry}). Passive
 * collectors + Prometheus export + transit-plane custom gauges. One port per
 * process; {@link StpKpi} mirrors into the Micrometer registry so
 * {@code /metrics} and the Monitor Hub show identical numbers.
 */
@ApplicationScoped
public class AppTelemetry {
    private static final Logger LOG = LogManager.getLogger(AppTelemetry.class);

    volatile TelemetryPort port;

    public TelemetryPort install(MicroSleeContainer container) {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        MicrometerTelemetryPort tp = new MicrometerTelemetryPort(registry, container);
        tp.start();
        container.getEventRouter().setDispatchObserver(new TelemetryDispatchObserver(tp));
        container.setRaObserver(new TelemetryRaObserver(tp));
        tp.customGauge("stp_relay_forwarded_total", () -> StpKpi.value("relay.forwarded"));
        tp.customGauge("stp_relay_rejected_total", () -> StpKpi.value("relay.rejected"));
        tp.customGauge("stp_acl_denied_total", () -> StpKpi.value("acl.denied"));
        tp.customGauge("stp_gtt_translated_total", () -> StpKpi.value("gtt.translated"));
        tp.customGauge("stp_gtt_unrouted_total", () -> StpKpi.value("gtt.unrouted"));
        StpKpi.bindPort(tp);
        this.port = tp;
        LOG.info("STP telemetry armed");
        return tp;
    }

    public TelemetryPort port() {
        return port;
    }

    /** Re-attach an armed port (test seam; boot uses {@link #install}). */
    public void attach(TelemetryPort telemetryPort) {
        this.port = telemetryPort;
    }

    /** Extra keys for the admin {@code /admin/monitor-feed} live tiles. */
    public Map<String, Object> feedExtras() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stp.relay.forwarded", StpKpi.value("relay.forwarded"));
        m.put("stp.relay.rejected", StpKpi.value("relay.rejected"));
        m.put("stp.acl.denied", StpKpi.value("acl.denied"));
        m.put("stp.gtt.translated", StpKpi.value("gtt.translated"));
        m.put("stp.gtt.unrouted", StpKpi.value("gtt.unrouted"));
        return m;
    }

    public void close() {
        TelemetryPort tp = port;
        port = null;
        StpKpi.bindPort(null);
        if (tp instanceof MicrometerTelemetryPort micrometer) {
            try {
                micrometer.stop();
            } catch (RuntimeException e) {
                LOG.debug("telemetry stop: {}", e.toString());
            }
        }
    }
}