package et.elisa.stp.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Loads {@link StpTransitProfile} from {@code configs/stp.json}.
 *
 * <p>Resolution order mirrors the GMLC config convention: explicit property
 * first, then {@code ./configs/stp.json} relative to the working directory.</p>
 */
public final class StpTransitConfigLoader {
    private static final ObjectMapper JSON = new ObjectMapper();
    public static final String DEFAULT_FILE = "configs/stp.json";

    private StpTransitConfigLoader() {}

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

    public static StpTransitConfig load(Path file) throws IOException {
        return parse(Files.readString(file));
    }

    /** Strict parse: unknown fields are rejected so typos never silently pass. */
    public static StpTransitConfig parse(String json) throws IOException {
        Dto dto = JSON.readValue(json, Dto.class);
        return dto.toConfig();
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    record Dto(
            @JsonProperty("stackName") String stackName,
            @JsonProperty("ha") HaDto ha,
            @JsonProperty("transit") TransitDto transit,
            @JsonProperty("acl") AclDto acl) {

        StpTransitConfig toConfig() {
            if (ha == null) {
                throw new IllegalArgumentException("stp.json: ha section required");
            }
            StpTransitConfig.Ha haP = new StpTransitConfig.Ha(
                    ha.mode, ha.nodeId, ha.dialogIdRangeStart, ha.dialogIdRangeEnd, ha.peers);
            StpTransitConfig.Transit transitP = transit == null
                    ? StpTransitConfig.Transit.defaults()
                    : new StpTransitConfig.Transit(
                            transit.enabled == null || transit.enabled,
                            transit.removeSpc == null || transit.removeSpc,
                            transit.maskGtInLogs == null || transit.maskGtInLogs);
            StpTransitConfig.Acl aclP = acl == null
                    ? new StpTransitConfig.Acl(null, null)
                    : new StpTransitConfig.Acl(acl.defaultAction,
                            acl.entries == null ? null : acl.entries.stream()
                                    .map(e -> new StpTransitConfig.Acl.AclEntry(
                                            e.action, e.dpc, e.gt, e.ssns))
                                    .toList());
            return new StpTransitConfig(stackName, haP, transitP, aclP);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    record HaDto(@JsonProperty("mode") String mode,
                 @JsonProperty("nodeId") String nodeId,
                 @JsonProperty("dialogIdRangeStart") long dialogIdRangeStart,
                 @JsonProperty("dialogIdRangeEnd") long dialogIdRangeEnd,
                 @JsonProperty("peers") List<String> peers) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    record TransitDto(@JsonProperty("enabled") Boolean enabled,
                      @JsonProperty("removeSpc") Boolean removeSpc,
                      @JsonProperty("maskGtInLogs") Boolean maskGtInLogs) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    record AclDto(@JsonProperty("defaultAction") String defaultAction,
                  @JsonProperty("entries") List<EntryDto> entries) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    record EntryDto(@JsonProperty("action") String action,
                    @JsonProperty("dpc") Integer dpc,
                    @JsonProperty("gt") String gt,
                    @JsonProperty("ssns") java.util.Set<Integer> ssns) {}
}
