package et.elisa.iwf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Parses configs/iwf.json (lenient: every section optional). */
public final class IwfConfigJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private IwfConfigJson() {
    }

    public static IwfConfig parse(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode dia = root.path("diameter");
            JsonNode map = root.path("map");
            IwfConfig.DiaLegConfig d = new IwfConfig.DiaLegConfig(
                    dia.path("draHost").asText("127.0.0.1"),
                    dia.path("draPort").asInt(3870),
                    dia.path("srcPort").asInt(38690),
                    dia.path("originHost").asText(
                            "iwf1.epc.mnc01.mcc452.3gppnetwork.org"),
                    dia.path("originRealm").asText(
                            "epc.mnc01.mcc452.3gppnetwork.org"),
                    dia.has("destHost") ? dia.path("destHost").asText(null)
                            : "dra1.epc.mnc01.mcc452.3gppnetwork.org",
                    dia.has("destRealm") ? dia.path("destRealm").asText(null) : null,
                    dia.path("responseTimeoutMillis").asLong(5_000L));
            IwfConfig.MapLegConfig m = new IwfConfig.MapLegConfig(
                    map.path("ssn").asInt(146),
                    map.path("ownGt").asText("0000000000"),
                    map.path("ownSpc").asText("0"));
            return new IwfConfig(d, m);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid iwf.json: " + e.getMessage(), e);
        }
    }

    public static IwfConfig load() {
        for (Path candidate : List.of(Path.of("configs/iwf.json"),
                Path.of("../configs/iwf.json"))) {
            if (Files.exists(candidate)) {
                try {
                    return parse(Files.readString(candidate));
                } catch (Exception e) {
                    throw new IllegalStateException("invalid " + candidate, e);
                }
            }
        }
        return new IwfConfig(null, null);
    }
}
