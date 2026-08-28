package et.elisa.fabric;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Fail-fast fabric validators (STP G6 lesson, design §8 guardrails). Every
 * validator either passes or throws {@link IllegalArgumentException} BEFORE the
 * first write — a bad record never reaches the shared memory.
 *
 * <p>Anchored to IR.88/TS 23.003: the canonical roaming realm is
 * {@code epc.mnc<MNC>.mcc<MCC>.3gppnetwork.org} (§3.1.3.4); IMSI/GtPrefix carry
 * the usual 3GPP numeric shapes.</p>
 */
public final class FabricValidator {

    private static final Pattern REALM =
            Pattern.compile("epc\\.mnc\\d{2,3}\\.mcc\\d{3}\\.3gppnetwork\\.org");
    private static final Pattern IMSI = Pattern.compile("\\d{15}");
    private static final Pattern GT_PREFIX = Pattern.compile("\\d{3,15}");
    private static final Pattern HOST = Pattern.compile("(?i)[a-z0-9]([a-z0-9.-]*[a-z0-9])?");
    private static final Pattern DIALOG_ID = Pattern.compile("[\\w.:;@\\-]{1,128}");
    private static final Pattern ENDPOINT_KEY = Pattern.compile("[\\w.:\\[\\]-]{1,128}");

    private FabricValidator() {
    }

    public static void validate(ElisaFabricCache def, String key, Map<String, Object> values) {
        Objects.requireNonNull(def, "def");
        Objects.requireNonNull(key, "key");
        if (key.isBlank()) {
            throw new IllegalArgumentException(def.cacheName() + " key is blank");
        }
        Objects.requireNonNull(values, "values for " + def.cacheName());
        switch (def) {
            case PEER_TOPOLOGY -> {
                validateRealmKey(def, key);
                require(def, values, "host", String.class);
                require(def, values, "port", Number.class);
                require(def, values, "transport", String.class);
            }
            case ROUTE_POLICY -> validateRouteKey(def, key);
            case TH_MAP -> validateHostKey(def, key);
            case GTT_PUBLIC -> {
                if (!GT_PREFIX.matcher(key).matches()) {
                    throw new IllegalArgumentException(
                            def.cacheName() + " key must be a numeric GT prefix, got '" + key + "'");
                }
                require(def, values, "pointCode", Number.class);
                require(def, values, "subsystemNumber", Number.class);
            }
            case PEER_STATE -> {
                Object state = require(def, values, "state", String.class);
                if (!java.util.Set.of("READY", "WARN", "DOWN").contains(state)) {
                    throw new IllegalArgumentException(
                            def.cacheName() + " state must be READY|WARN|DOWN, got " + state);
                }
            }
            case IMSI_CONTEXT -> {
                if (!IMSI.matcher(key).matches()) {
                    throw new IllegalArgumentException(
                            def.cacheName() + " key must be a 15-digit IMSI, got '" + key + "'");
                }
                require(def, values, "ingressPeer", String.class);
                require(def, values, "dialOwnerNodeId", String.class);
            }
            case DIALOG_BIND -> {
                if (!DIALOG_ID.matcher(key).matches()) {
                    throw new IllegalArgumentException(
                            def.cacheName() + " key must be a dialog/session id, got '" + key + "'");
                }
                String imsi = (String) require(def, values, "imsi", String.class);
                if (!IMSI.matcher(imsi).matches()) {
                    throw new IllegalArgumentException(
                            def.cacheName() + " imsi must be 15 digits, got '" + imsi + "'");
                }
            }
            case CONFIG -> {
                // free-form validated config vé; only require non-blank values
                for (Map.Entry<String, Object> e : values.entrySet()) {
                    if (e.getValue() instanceof String s && s.isBlank()) {
                        throw new IllegalArgumentException(
                                "elisa/config key '" + e.getKey() + "' has blank value");
                    }
                }
            }
            case LEASES -> {
                if (!ENDPOINT_KEY.matcher(key).matches()) {
                    throw new IllegalArgumentException(
                            def.cacheName() + " key must be an ip:port endpoint, got '" + key + "'");
                }
                require(def, values, "nodeId", String.class);
                require(def, values, "leaseUntilMs", Number.class);
            }
        }
    }

    private static void validateRealmKey(ElisaFabricCache def, String key) {
        if (REALM.matcher(key).matches()) {
            return;
        }
        // IR.88 §3.1.3.4 allows the standard realm; the static IR.21-like table
        // may carry a private test realm — accept host-like keys, reject the rest.
        if (!key.contains(" ") && HOST.matcher(key).matches()) {
            return;
        }
        throw new IllegalArgumentException(
                def.cacheName() + " key must be a canonical realm or host, got '" + key + "'");
    }

    private static void validateRouteKey(ElisaFabricCache def, String key) {
        if (key.contains("/")) {
            String[] parts = key.split("/", 2);
            validateRealmKey(def, parts[0]);
            requireAppId(def, parts[1]);
            return;
        }
        // bare appId or plain realm key
        if (key.chars().allMatch(Character::isDigit)) {
            requireAppId(def, key);
        } else {
            validateRealmKey(def, key);
        }
    }

    private static void requireAppId(ElisaFabricCache def, String appId) {
        try {
            int app = Integer.parseInt(appId);
            if (app <= 0) {
                throw new IllegalArgumentException(def.cacheName() + " appId must be > 0");
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    def.cacheName() + " appId must be a positive integer, got '" + appId + "'", e);
        }
    }

    private static void validateHostKey(ElisaFabricCache def, String key) {
        if (!HOST.matcher(key).matches()) {
            throw new IllegalArgumentException(
                    def.cacheName() + " key must be a host, got '" + key + "'");
        }
    }

    private static Object require(ElisaFabricCache def, Map<String, Object> values,
                                  String field, Class<?> type) {
        Object v = values.get(field);
        if (v == null) {
            throw new IllegalArgumentException(def.cacheName() + " missing required field '" + field + "'");
        }
        if (!type.isInstance(v)) {
            throw new IllegalArgumentException(
                    def.cacheName() + " field '" + field + "' must be " + type.getSimpleName()
                            + ", got " + v.getClass().getName());
        }
        return v;
    }
}