package et.elisa.stp.telemetry;

import com.microjainslee.telemetry.TelemetryPort;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Transit-plane counters for the Nextgen STP (relay / GTT / ACL / congestion).
 *
 * <p>Source of truth is a lock-free {@link LongAdder} map rendered by the
 * Monitor Hub KPI panel and mirrored into Micrometer so {@code /metrics}
 * carries the same numbers ({@code stp_kpi_*}). Counters live for the process
 * lifetime only — restart resets them by design (DESIGN.md: stateless relay).</p>
 */
public final class StpKpi {
    private static final ConcurrentHashMap<String, LongAdder> COUNTERS = new ConcurrentHashMap<>();
    private static volatile TelemetryPort port;

    private StpKpi() {
    }

    public static void bindPort(TelemetryPort telemetryPort) {
        port = telemetryPort;
    }

    public static void inc(String key) {
        COUNTERS.computeIfAbsent(key, k -> new LongAdder()).increment();
        TelemetryPort p = port;
        if (p != null) {
            try {
                p.customCounter(metricName(key)).increment();
            } catch (RuntimeException ignored) {
                // telemetry mirror must never break the data plane
            }
        }
    }

    public static long value(String key) {
        LongAdder adder = COUNTERS.get(key);
        return adder == null ? 0L : adder.sum();
    }

    /** Sorted live view for JSON + HTML rendering. */
    public static Map<String, Long> snapshot() {
        TreeMap<String, Long> out = new TreeMap<>();
        COUNTERS.forEach((k, v) -> out.put(k, v.sum()));
        return out;
    }

    public static void resetForTests() {
        COUNTERS.clear();
    }

    /** Prometheus-safe metric name: {@code relay.forwarded} → {@code stp_kpi_relay_forwarded}. */
    public static String metricName(String key) {
        return "stp_kpi_" + key.toLowerCase().replaceAll("[^a-z0-9]+", "_");
    }

    // ── convenience emitters ──

    /** One SCCP unit-data message successfully relayed to its GTT-resolved DPC. */
    public static void relayForwarded() {
        inc("relay.forwarded");
    }

    /** One SCCP unit-data message dropped by the receive/relay path. */
    public static void relayRejected(String reason) {
        inc("relay.rejected");
        inc("relay.rejected." + safe(reason));
    }

    /** One ingress unit-data message denied by the default-deny incoming ACL. */
    public static void aclDenied(int opc) {
        inc("acl.denied");
        inc("acl.denied.opc." + opc);
    }

    /** One global title resolved to a destination PC+SSN (GTT hit). */
    public static void gtTranslated() {
        inc("gtt.translated");
    }

    /** One GT that could not be resolved (no matching rule). */
    public static void gtUnrouted() {
        inc("gtt.unrouted");
    }

    private static String safe(String s) {
        if (s == null || s.isBlank()) {
            return "unspecified";
        }
        return s.replaceAll("[^a-zA-Z0-9._-]+", "_");
    }
}