package et.elisa.stp.gtt;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.restcomm.protocols.ss7.indicator.NatureOfAddress;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Loads {@link GttFixtures} from {@code configs/gtt.json} (module convention
 * mirrors {@link et.elisa.stp.config.StpTransitConfigLoader}).
 *
 * <p>Resolution order: explicit property first, then {@code ./configs/gtt.json}
 * relative to the working directory. Parsing is strict — unknown JSON fields
 * are rejected so typos never silently pass.</p>
 */
public final class GttFixtureLoader {
    private static final ObjectMapper JSON = new ObjectMapper();
    public static final String DEFAULT_FILE = "configs/gtt.json";

    private GttFixtureLoader() {}

    /** First existing candidate wins; empty when none found. */
    public static Optional<Path> resolveCandidate(String configured) {
        List<String> candidates = configured == null || configured.isBlank()
                ? List.of(DEFAULT_FILE)
                : List.of(configured, DEFAULT_FILE);
        for (String c : candidates) {
            Path p = Path.of(c);
            if (Files.isRegularFile(p)) {
                return Optional.of(p.toAbsolutePath().normalize());
            }
        }
        return Optional.empty();
    }

    public static GttFixtures load(Path file) throws IOException {
        return parse(Files.readString(file));
    }

    /** Strict parse: unknown fields are rejected so typos never silently pass. */
    public static GttFixtures parse(String json) throws IOException {
        Dto dto = JSON.readValue(json, Dto.class);
        return dto.toFixtures();
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    record Dto(
            @JsonProperty("schema") String schema,
            @JsonProperty("description") String description,
            @JsonProperty("networkId") Integer networkId,
            @JsonProperty("rules") List<RuleDto> rules,
            @JsonProperty("negativeGts") List<String> negativeGts) {

        GttFixtures toFixtures() {
            int defaultNetworkId = networkId == null ? 0 : networkId;
            List<GttFixtures.GttRule> mapped = rules == null ? List.of()
                    : rules.stream().map(r -> r.toRule(defaultNetworkId)).toList();
            return new GttFixtures(schema, description, defaultNetworkId, mapped, negativeGts);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    record RuleDto(
            @JsonProperty("ruleId") Integer ruleId,
            @JsonProperty("service") String service,
            @JsonProperty("description") String description,
            @JsonProperty("gtPattern") String gtPattern,
            @JsonProperty("gti") String gti,
            @JsonProperty("natureOfAddress") String natureOfAddress,
            @JsonProperty("dpc") Integer dpc,
            @JsonProperty("ssn") Integer ssn,
            @JsonProperty("networkId") Integer networkId) {

        GttFixtures.GttRule toRule(int defaultNetworkId) {
            int id = ruleId == null ? 0 : ruleId;
            NatureOfAddress noa = parseNoa(id, natureOfAddress);
            return new GttFixtures.GttRule(
                    id,
                    service,
                    description,
                    gtPattern,
                    gti,
                    noa,
                    dpc == null ? 0 : dpc,
                    ssn == null ? 0 : ssn,
                    networkId == null ? defaultNetworkId : networkId);
        }

        private static NatureOfAddress parseNoa(int ruleId, String raw) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException(
                        "gtt rule " + ruleId + ": natureOfAddress required");
            }
            try {
                return NatureOfAddress.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "gtt rule " + ruleId + ": unknown natureOfAddress " + raw);
            }
        }
    }
}