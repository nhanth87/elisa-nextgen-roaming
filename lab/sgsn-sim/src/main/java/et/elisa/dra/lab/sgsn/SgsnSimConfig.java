package et.elisa.dra.lab.sgsn;

import java.util.Objects;

/**
 * SGSN-sim lab configuration. Loaded from env / system properties in {@link SgsnSimMain};
 * a single instance describes how the lab client dials the STP and what
 * address it presents to the IWF on the other side of the bridge.
 *
 * <p>Field defaults mirror {@code docs/design/integration-stp-iwf-dra.md}
 * §3–§4 (SGSN-sim OPC=100 → STP OPC=10, IWF internal PC=250, SSN=11).</p>
 */
public record SgsnSimConfig(
        String stackName,
        String hostIp,
        int hostPort,
        String peerIp,
        int peerPort,
        int originatingPointCode,
        int destinationPointCode,
        int localSsn,
        int remoteSsn,
        long routingContext,
        int networkIndicator,
        String iwfGt,
        int dialogIdleTimeoutMs,
        int invokeTimeoutMs) {

    public SgsnSimConfig {
        Objects.requireNonNull(stackName, "stackName");
        Objects.requireNonNull(peerIp, "peerIp");
        if (hostPort <= 0) {
            throw new IllegalArgumentException("hostPort must be > 0");
        }
        if (peerPort <= 0) {
            throw new IllegalArgumentException("peerPort must be > 0");
        }
        if (originatingPointCode <= 0) {
            throw new IllegalArgumentException("originatingPointCode must be > 0");
        }
        if (destinationPointCode <= 0) {
            throw new IllegalArgumentException("destinationPointCode must be > 0");
        }
        if (localSsn <= 0 || localSsn > 255) {
            throw new IllegalArgumentException("localSsn out of range (1..255)");
        }
        if (remoteSsn <= 0 || remoteSsn > 255) {
            throw new IllegalArgumentException("remoteSsn out of range (1..255)");
        }
        iwfGt = iwfGt == null || iwfGt.isBlank() ? "8860123456001" : iwfGt;
        dialogIdleTimeoutMs = dialogIdleTimeoutMs <= 0 ? 30_000 : dialogIdleTimeoutMs;
        invokeTimeoutMs = invokeTimeoutMs <= 0 ? 10_000 : invokeTimeoutMs;
    }

    public static SgsnSimConfig fromEnv() {
        return new SgsnSimConfig(
                env("SGSN_STACK_NAME", "sgsn-sim-1"),
                env("SGSN_HOST_IP", "127.0.0.1"),
                intEnv("SGSN_HOST_PORT", 2904),
                env("SGSN_PEER_IP", "127.0.0.1"),
                intEnv("SGSN_PEER_PORT", 2905),
                intEnv("SGSN_OPC", 100),
                intEnv("SGSN_DPC", 10),
                intEnv("SGSN_LOCAL_SSN", 149),
                intEnv("SGSN_REMOTE_SSN", 11),
                longEnv("SGSN_ROUTING_CONTEXT", 100L),
                intEnv("SGSN_NETWORK_INDICATOR", 0),
                env("SGSN_IWF_GT", "8860123456001"),
                intEnv("SGSN_DIALOG_IDLE_MS", 30_000),
                intEnv("SGSN_INVOKE_TIMEOUT_MS", 10_000));
    }

    private static String env(String k, String def) {
        String v = System.getProperty(k);
        if (v == null) v = System.getenv(k);
        return v == null || v.isBlank() ? def : v;
    }

    private static int intEnv(String k, int def) {
        String v = env(k, null);
        if (v == null) return def;
        try { return Integer.parseInt(v.trim()); }
        catch (NumberFormatException e) {
            throw new IllegalArgumentException("env " + k + " is not an int: " + v);
        }
    }

    private static long longEnv(String k, long def) {
        String v = env(k, null);
        if (v == null) return def;
        try { return Long.parseLong(v.trim()); }
        catch (NumberFormatException e) {
            throw new IllegalArgumentException("env " + k + " is not a long: " + v);
        }
    }
}
