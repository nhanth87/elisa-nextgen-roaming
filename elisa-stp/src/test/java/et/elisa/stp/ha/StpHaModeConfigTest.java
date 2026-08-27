package et.elisa.stp.ha;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microjainslee.ra.jss7.Ss7RaConfig;
import com.microjainslee.ra.jss7.StpTransitProfile;
import et.elisa.stp.config.StpTransitConfig;
import et.elisa.stp.config.StpTransitConfigLoader;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * Validates HA mode configuration parsing for the Nextgen STP —
 * both ACTIVE_ACTIVE and ACTIVE_STANDBY modes, plus guardrails
 * (broadcast rejection, override-vs-HA-mode mismatch warning).
 *
 * <p>Covers DESIGN §4 dual-mode matrix config section and §10.2/P4
 * broadcast/override guardrails.</p>
 */
class StpHaModeConfigTest {

    @Test
    void activeActiveModeParsedFromStpJson() throws IOException {
        StpTransitConfig cfg = StpTransitConfigLoader.parse("""
                {
                  "ha": { "mode": "ACTIVE_ACTIVE", "nodeId": "stp-node-1" },
                  "transit": { "enabled": true, "removeSpc": true, "maskGtInLogs": true },
                  "acl": { "defaultAction": "DROP_SILENT", "entries": [] }
                }
                """);

        assertThat(cfg.ha().mode()).isEqualTo("ACTIVE_ACTIVE");
        assertThat(cfg.ha().activeActive()).isTrue();
        assertThat(cfg.ha().raHaMode()).isEqualTo(StpTransitProfile.HaMode.ACTIVE_ACTIVE);

        StpTransitProfile profile = cfg.toRaProfile();
        assertThat(profile.haMode()).isEqualTo(StpTransitProfile.HaMode.ACTIVE_ACTIVE);
    }

    @Test
    void activeStandbyModeParsedFromStpJson() throws IOException {
        StpTransitConfig cfg = StpTransitConfigLoader.parse("""
                {
                  "ha": { "mode": "ACTIVE_STANDBY", "nodeId": "stp-node-2" },
                  "transit": { "enabled": true, "removeSpc": true, "maskGtInLogs": true },
                  "acl": { "defaultAction": "DROP_SILENT", "entries": [] }
                }
                """);

        assertThat(cfg.ha().mode()).isEqualTo("ACTIVE_STANDBY");
        assertThat(cfg.ha().activeActive()).isFalse();
        assertThat(cfg.ha().raHaMode()).isEqualTo(StpTransitProfile.HaMode.ACTIVE_STANDBY);

        StpTransitProfile profile = cfg.toRaProfile();
        assertThat(profile.haMode()).isEqualTo(StpTransitProfile.HaMode.ACTIVE_STANDBY);
    }

    @Test
    void haModeIsNormalizedToUpper() throws IOException {
        StpTransitConfig cfg = StpTransitConfigLoader.parse("""
                {
                  "ha": { "mode": "active-standby", "nodeId": "n2" },
                  "transit": { "enabled": true },
                  "acl": { "defaultAction": "DROP_SILENT", "entries": [] }
                }
                """);

        assertThat(cfg.ha().mode()).isEqualTo("ACTIVE_STANDBY");
        assertThat(cfg.ha().raHaMode()).isEqualTo(StpTransitProfile.HaMode.ACTIVE_STANDBY);
    }

    @Test
    void invalidHaModeRejected() {
        assertThatThrownBy(() -> StpTransitConfigLoader.parse("""
                {
                  "ha": { "mode": "BROADCAST_ONLY" },
                  "transit": { "enabled": true },
                  "acl": { "defaultAction": "DROP_SILENT", "entries": [] }
                }
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stp ha.mode invalid");
    }

    @Test
    void blankHaModeRejected() {
        assertThatThrownBy(() -> StpTransitConfigLoader.parse("""
                {
                  "ha": { "mode": "  " },
                  "transit": { "enabled": true },
                  "acl": { "defaultAction": "DROP_SILENT", "entries": [] }
                }
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stp ha.mode required");
    }

    @Test
    void missingHaSectionRejected() {
        assertThatThrownBy(() -> StpTransitConfigLoader.parse("""
                {
                  "transit": { "enabled": true },
                  "acl": { "defaultAction": "DROP_SILENT", "entries": [] }
                }
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ha section required");
    }

    @Test
    void dialogIdRangeValidatedInHaSection() {
        assertThatThrownBy(() -> StpTransitConfigLoader.parse("""
                {
                  "ha": { "mode": "ACTIVE_ACTIVE", "nodeId": "n1",
                          "dialogIdRangeStart": 5000, "dialogIdRangeEnd": 1000 },
                  "transit": { "enabled": true },
                  "acl": { "defaultAction": "DROP_SILENT", "entries": [] }
                }
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dialogIdRangeEnd < dialogIdRangeStart");
    }

    @Test
    void validDialogIdRangeAccepted() throws IOException {
        StpTransitConfig cfg = StpTransitConfigLoader.parse("""
                {
                  "ha": { "mode": "ACTIVE_STANDBY", "nodeId": "n1",
                          "dialogIdRangeStart": 1000, "dialogIdRangeEnd": 5000 },
                  "transit": { "enabled": true },
                  "acl": { "defaultAction": "DROP_SILENT", "entries": [] }
                }
                """);

        assertThat(cfg.ha().dialogIdRangeStart()).isEqualTo(1000);
        assertThat(cfg.ha().dialogIdRangeEnd()).isEqualTo(5000);
    }

    @Test
    void peersListParsed() throws IOException {
        StpTransitConfig cfg = StpTransitConfigLoader.parse("""
                {
                  "ha": { "mode": "ACTIVE_ACTIVE", "nodeId": "n1",
                          "peers": ["n2", "n3"] },
                  "transit": { "enabled": true },
                  "acl": { "defaultAction": "DROP_SILENT", "entries": [] }
                }
                """);

        assertThat(cfg.ha().peers()).containsExactly("n2", "n3");
    }

    @Test
    void sctpEndpointIndexAndLocalEndpointsParsed() {
        Ss7RaConfig cfg = new Ss7RaConfig()
                .sctpLocalEndpoints(java.util.List.of("10.0.0.1:2905", "10.0.0.2:2905"))
                .sctpEndpointIndex(1);

        assertThat(cfg.resolvedLocalEndpoint()).isEqualTo("10.0.0.2:2905");
        assertThat(cfg.allLocalEndpoints()).containsExactly("10.0.0.1:2905", "10.0.0.2:2905");
    }

    @Test
    void endpointIndexOutOfRangeRejected() {
        Ss7RaConfig cfg = new Ss7RaConfig()
                .sctpLocalEndpoints(java.util.List.of("10.0.0.1:2905"))
                .sctpEndpointIndex(3);

        assertThatThrownBy(cfg::resolvedLocalEndpoint)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("out of range");
    }

    @Test
    void broadcastTrafficModeRejected() {
        Ss7RaConfig cfg = new Ss7RaConfig();
        assertThatThrownBy(() -> cfg.defaultTrafficMode("broadcast"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("broadcast forbidden");
    }

    @Test
    void loadshareTrafficModeAccepted() {
        Ss7RaConfig cfg = new Ss7RaConfig().defaultTrafficMode("loadshare");
        assertThat(cfg.defaultTrafficMode()).isEqualTo("loadshare");
    }

    @Test
    void overrideTrafficModeAccepted() {
        Ss7RaConfig cfg = new Ss7RaConfig().defaultTrafficMode("override");
        assertThat(cfg.defaultTrafficMode()).isEqualTo("override");
    }

    @Test
    void overrideWithActiveActiveEmitsWarning() {
        Ss7RaConfig cfg = new Ss7RaConfig().defaultTrafficMode("override");
        boolean warned = cfg.warnIfOverrideTrafficMode(StpTransitProfile.HaMode.ACTIVE_ACTIVE);
        assertThat(warned).isTrue();
    }

    @Test
    void overrideWithActiveStandbyNoWarning() {
        Ss7RaConfig cfg = new Ss7RaConfig().defaultTrafficMode("override");
        boolean warned = cfg.warnIfOverrideTrafficMode(StpTransitProfile.HaMode.ACTIVE_STANDBY);
        assertThat(warned).isFalse();
    }

    @Test
    void loadshareWithActiveActiveNoWarning() {
        Ss7RaConfig cfg = new Ss7RaConfig().defaultTrafficMode("loadshare");
        boolean warned = cfg.warnIfOverrideTrafficMode(StpTransitProfile.HaMode.ACTIVE_ACTIVE);
        assertThat(warned).isFalse();
    }

    @Test
    void restrictionLevelBoundsEnforced() {
        Ss7RaConfig cfg = new Ss7RaConfig();
        assertThatThrownBy(() -> cfg.defaultRestrictionLevel(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cfg.defaultRestrictionLevel(9))
                .isInstanceOf(IllegalArgumentException.class);
        cfg.defaultRestrictionLevel(5);
        assertThat(cfg.defaultRestrictionLevel()).isEqualTo(5);
    }

    @Test
    void dialogIdRangeValidationOnConfig() {
        Ss7RaConfig cfg = new Ss7RaConfig()
                .dialogIdRangeStart(1000)
                .dialogIdRangeEnd(500);
        assertThatThrownBy(cfg::validateDialogIdRange)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("start>0 and end>start");
    }

    @Test
    void dialogIdRangeDefaultsSkipValidation() {
        Ss7RaConfig cfg = new Ss7RaConfig();
        cfg.validateDialogIdRange();
    }
}
