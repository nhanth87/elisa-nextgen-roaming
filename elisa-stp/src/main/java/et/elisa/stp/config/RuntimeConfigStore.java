package et.elisa.stp.config;

import jakarta.enterprise.context.ApplicationScoped;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory runtime config overlay for the STP (no database — the transit
 * plane must stay stateless). Values come from environment / properties at
 * boot; {@link #put} is only used by the admin UI within process lifetime.
 */
@ApplicationScoped
public class RuntimeConfigStore {
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    public Optional<String> get(String key) {
        if (key == null) return Optional.empty();
        String v = cache.get(key);
        return v == null || v.isBlank() ? Optional.empty() : Optional.of(v);
    }

    public String getOr(String key, String def) {
        return get(key).orElse(def);
    }

    public boolean getBool(String key, boolean def) {
        return get(key).map(s -> "true".equalsIgnoreCase(s.trim()) || "1".equals(s.trim())).orElse(def);
    }

    public int getInt(String key, int def) {
        return get(key).map(s -> {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
        }).orElse(def);
    }

    public boolean anyPresent(String... keys) {
        if (keys == null) return false;
        for (String k : keys) {
            if (get(k).isPresent()) return true;
        }
        return false;
    }

    public void put(String key, String value) {
        if (key == null || key.isBlank()) return;
        cache.put(key.trim(), value == null ? "" : value);
    }

    public Map<String, String> snapshot() {
        return Map.copyOf(cache);
    }

    public static final class Keys {
        private Keys() {}
        public static final String SS7_HOST_IP = "stp.ss7.host-ip";
        public static final String SS7_HOST_PORT = "stp.ss7.host-port";
        public static final String SS7_PEER_IP = "stp.ss7.peer-ip";
        public static final String SS7_PEER_PORT = "stp.ss7.peer-port";
        public static final String SS7_OPC = "stp.ss7.opc";
        public static final String SS7_DPC = "stp.ss7.dpc";
        public static final String SS7_CHANNEL = "stp.ss7.ip-channel-type";
        public static final String SS7_CONFIG_FILE = "stp.ss7.config-file";
        public static final String SS7_JSON = "ss7.json";
        public static final String STP_TRANSIT_CONFIG = "stp.transit.config-file";
        public static final String HTTP_RA_HOST = "http.ra.host";
        public static final String HTTP_RA_PORT = "http.ra.port";
        public static final String HTTP_RA_EVENT_LOOP = "http.ra.event-loop-threads";
        public static final String HTTP_RA_WORKER_POOL = "http.ra.worker-pool-size";
        public static final String HTTP_RA_ACCEPT_BACKLOG = "http.ra.accept-backlog";

        public static String[] ss7() {
            return new String[]{SS7_HOST_IP, SS7_HOST_PORT, SS7_PEER_IP, SS7_PEER_PORT,
                    SS7_OPC, SS7_DPC, SS7_CHANNEL, SS7_CONFIG_FILE, SS7_JSON};
        }
    }
}
