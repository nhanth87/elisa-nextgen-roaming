package et.elisa.iwf;

import java.util.Map;

/** IWF runtime configuration (configs/iwf.json). */
public record IwfConfig(DiaLegConfig diameter, MapLegConfig map) {

    public record DiaLegConfig(String draHost, int draPort, int srcPort,
                               String originHost, String originRealm,
                               String destHost, String destRealm,
                               long responseTimeoutMillis) {

        public DiaLegConfig {
            if (draHost == null || draHost.isBlank()) {
                throw new IllegalArgumentException("diameter.draHost required");
            }
            if (draPort < 1 || draPort > 65535) {
                throw new IllegalArgumentException("diameter.draPort out of range");
            }
            if (srcPort < 0 || srcPort > 65535) {
                throw new IllegalArgumentException("diameter.srcPort out of range");
            }
            if (originHost == null || originHost.isBlank()) {
                throw new IllegalArgumentException("diameter.originHost required");
            }
            if (originRealm == null || originRealm.isBlank()) {
                throw new IllegalArgumentException("diameter.originRealm required");
            }
            if (destHost == null || destHost.isBlank()) {
                throw new IllegalArgumentException(
                        "diameter.destHost required (DRA identity host)");
            }
            destRealm = destRealm == null || destRealm.isBlank() ? originRealm : destRealm;
            if (responseTimeoutMillis <= 0) {
                responseTimeoutMillis = 5_000L;
            }
        }
    }

    public record MapLegConfig(int ssn, String ownGt, String ownSpc) {
        public MapLegConfig {
            if (ssn < 1 || ssn > 255) {
                throw new IllegalArgumentException(
                        "map.ssn must be 1-255 (SSN per TS 29.002): " + ssn);
            }
            if (ownGt == null || ownGt.isBlank()) {
                throw new IllegalArgumentException("map.ownGt required");
            }
            if (ownGt.contains("TBD")) {
                throw new IllegalArgumentException(
                        "map.ownGt must be a real GT value, not placeholder: " + ownGt);
            }
            if (ownSpc == null || ownSpc.isBlank()) {
                throw new IllegalArgumentException("map.ownSpc required");
            }
            if (ownSpc.contains("TBD")) {
                throw new IllegalArgumentException(
                        "map.ownSpc must be a real SPC value, not placeholder: " + ownSpc);
            }
        }
    }

    public IwfConfig {
        if (diameter == null) {
            diameter = new DiaLegConfig("127.0.0.1", 3870, 38690,
                    "iwf1.epc.mnc01.mcc452.3gppnetwork.org",
                    "epc.mnc01.mcc452.3gppnetwork.org",
                    "dra1.epc.mnc01.mcc452.3gppnetwork.org",
                    "epc.mnc01.mcc452.3gppnetwork.org", 5_000L);
        }
        if (map == null) {
            map = new MapLegConfig(146, "0000000000", "0");
        }
    }

    public static Map<String, Object> asMap(IwfConfig c) {
        return Map.of(
                "diameter", Map.of(
                        "draHost", c.diameter.draHost(),
                        "draPort", c.diameter.draPort(),
                        "srcPort", c.diameter.srcPort(),
                        "originHost", c.diameter.originHost(),
                        "originRealm", c.diameter.originRealm(),
                        "destHost", c.diameter.destHost(),
                        "destRealm", c.diameter.destRealm(),
                        "responseTimeoutMillis", c.diameter.responseTimeoutMillis()),
                "map", Map.of(
                        "ssn", c.map.ssn(),
                        "ownGt", c.map.ownGt(),
                        "ownSpc", c.map.ownSpc()));
    }
}
