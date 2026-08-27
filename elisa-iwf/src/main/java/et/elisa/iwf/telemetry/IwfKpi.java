package et.elisa.iwf.telemetry;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import com.microjainslee.telemetry.TelemetryPort;

/**
 * Protocol-level IWF counters — MAP requests/responses per operation,
 * Diameter requests/responses per operation, TCAP dialog outcomes,
 * binding lifecycle events, and mapping engine dispatches.
 *
 * <p>Source of truth is an in-memory {@link LongAdder} map (fast, lock-free,
 * rendered by the Monitor Hub KPI panel). Every key is mirrored into the
 * Micrometer registry as a Prometheus counter when the telemetry port is
 * armed. Counters reset on process restart by design.</p>
 */
public final class IwfKpi {

    private static final ConcurrentHashMap<String, LongAdder> COUNTERS = new ConcurrentHashMap<>();
    private static volatile TelemetryPort port;

    private IwfKpi() {
    }

    /** Called from {@link AppTelemetry#install} / {@link AppTelemetry#close}. */
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

    /** Prometheus-safe metric name: {@code map.request.UGL} -> {@code iwf_kpi_map_request_ugl}. */
    public static String metricName(String key) {
        return "iwf_kpi_" + key.toLowerCase().replaceAll("[^a-z0-9]+", "_");
    }

    // ── convenience emitters ──

    /** One outbound MAP request (server-initiated from Diameter side). */
    public static void mapRequest(String operation) {
        String op = safeOp(operation);
        inc("map.request");
        inc("map.request." + op);
    }

    /** Decoded successful MAP response. */
    public static void mapResponseSuccess(String operation) {
        String op = safeOp(operation);
        inc("map.response.success");
        inc("map.response.success." + op);
    }

    /** MAP-level failure: error return, abort, missing IE. */
    public static void mapResponseFail(String operation) {
        String op = safeOp(operation);
        inc("map.response.fail");
        inc("map.response.fail." + op);
    }

    /** One outbound Diameter request (client-initiated from MAP side). */
    public static void diaRequest(String operation) {
        String op = safeOp(operation);
        inc("dia.request");
        inc("dia.request." + op);
    }

    /** Decoded successful Diameter response. */
    public static void diaResponseSuccess(String operation) {
        String op = safeOp(operation);
        inc("dia.response.success");
        inc("dia.response.success." + op);
    }

    /** Diameter-level failure. */
    public static void diaResponseFail(String operation) {
        String op = safeOp(operation);
        inc("dia.response.fail");
        inc("dia.response.fail." + op);
    }

    /** TCAP/dialog lifecycle notification. */
    public static void tcapDialog(String kind) {
        String k = kind == null ? "unknown" : kind.toLowerCase().replace(' ', '_');
        inc("tcap.dialog");
        inc("tcap.dialog." + k);
    }

    /** Binding lifecycle event. */
    public static void binding(String event) {
        String e = event == null ? "unknown" : event.toLowerCase();
        inc("binding." + e);
    }

    /** Mapping engine dispatch. */
    public static void mappingDispatch(String operation) {
        String op = safeOp(operation);
        inc("mapping.dispatch");
        inc("mapping.dispatch." + op);
    }

    private static String safeOp(String operation) {
        return operation == null || operation.isBlank()
                ? "unknown"
                : operation.trim().toLowerCase().replace('-', '_');
    }
}
