package et.elisa.stp.service;

import et.elisa.stp.admin.LinkStatusService;
import et.elisa.stp.config.RuntimeConfigStore;
import et.elisa.stp.config.StpTransitConfig;
import et.elisa.stp.config.StpTransitConfigLoader;

import com.microjainslee.ra.jss7.Ss7ResourceAdaptor;
import com.microjainslee.ra.jss7.StpTransitProfile;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.file.Path;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Loads {@code configs/stp.json} and stamps the ra-jss7
 * {@link StpTransitProfile} (canRelay / removeSpc / incoming ACL / HA mode)
 * onto the SS7 adaptor before it activates.
 *
 * <p>The profile must be set <b>before</b> {@code registerRa()} — the RA
 * applies it inside {@code raActive()} once the SCCP stack is RUNNING.</p>
 */
@ApplicationScoped
public class StpTransitApplyService {
    private static final Logger LOG = LogManager.getLogger(StpTransitApplyService.class);

    @Inject LinkStatusService linkStatus;
    @Inject RuntimeConfigStore store;

    @ConfigProperty(name = "stp.transit.config-file")
    Optional<String> transitConfigFileProp;
    @ConfigProperty(name = "stp.transit.enabled", defaultValue = "true")
    boolean transitEnabledProp;

    private volatile StpTransitConfig config;
    private volatile Path sourceFile;

    @PostConstruct
    void load() {
        String configured = store.get(RuntimeConfigStore.Keys.STP_TRANSIT_CONFIG)
                .orElse(transitConfigFileProp.orElse(null));
        Optional<Path> candidate = StpTransitConfigLoader.resolveCandidate(configured);
        if (candidate.isEmpty()) {
            LOG.info("No {} found — STP transit profile not applied "
                    + "(ra-jss7 defaults: relay disabled)", StpTransitConfigLoader.DEFAULT_FILE);
            return;
        }
        try {
            config = StpTransitConfigLoader.load(candidate.get());
            sourceFile = candidate.get();
            StpTransitConfig.Ha ha = config.ha();
            linkStatus.setHa(ha == null ? "?" : ha.nodeId(), ha == null ? "?" : ha.mode());
            LOG.info("STP transit config {}: node={} ha={} transit={} acl.default={}",
                    sourceFile, ha == null ? "?" : ha.nodeId(),
                    ha == null ? "?" : ha.mode(),
                    config.transit().enabled(),
                    config.acl().defaultAction());
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Invalid STP transit config " + candidate.get() + ": " + ex.getMessage(), ex);
        }
    }

    /**
     * Re-read {@code configs/stp.json} from disk. Called before every
     * {@link #applyToRa} so the admin <em>Apply</em> action (and boot) always
     * stamps the CURRENT posture — ACL edits land without a process restart.
     * A broken edit keeps the previous profile instead of killing the plane.
     */
    synchronized void reloadFromDisk() {
        String configured = store.get(RuntimeConfigStore.Keys.STP_TRANSIT_CONFIG)
                .orElse(transitConfigFileProp.orElse(null));
        Optional<Path> candidate = StpTransitConfigLoader.resolveCandidate(configured);
        if (candidate.isEmpty()) return;
        try {
            StpTransitConfig fresh = StpTransitConfigLoader.load(candidate.get());
            this.config = fresh;
            this.sourceFile = candidate.get();
            StpTransitConfig.Ha ha = fresh.ha();
            linkStatus.setHa(ha == null ? "?" : ha.nodeId(), ha == null ? "?" : ha.mode());
            LOG.info("STP transit config reloaded {}: aclPeers={}",
                    sourceFile, fresh.acl().entries() == null ? 0 : fresh.acl().entries().size());
        } catch (Exception ex) {
            LOG.warn("STP transit config reload failed (keeping previous profile): {}", ex.getMessage());
        }
    }

    /** Stamp the transit profile on the adaptor (no-op when transit disabled). */
    public void applyToRa(Ss7ResourceAdaptor ra) {
        reloadFromDisk();
        StpTransitConfig cfg = config;
        if (!transitEnabled() || cfg == null) {
            LOG.info("STP transit profile not applied (enabled={} config={})",
                    transitEnabledProp, cfg == null ? "absent" : "present");
            return;
        }
        StpTransitProfile profile = cfg.toRaProfile();
        ra.setStpTransitProfile(profile);
        LOG.info("STP transit profile applied: relay={} removeSpc={} ha={} aclPeers={}",
                profile.transitEnabled(), profile.removeSpcOnRelay(),
                profile.haMode(), profile.aclRules().size());
    }

    public boolean transitEnabled() {
        return transitEnabledProp && config != null && config.transit().enabled();
    }

    public StpTransitConfig config() { return config; }

    public Path sourceFile() { return sourceFile; }
}
