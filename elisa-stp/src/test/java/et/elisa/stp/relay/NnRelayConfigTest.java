package et.elisa.stp.relay;

import static org.assertj.core.api.Assertions.assertThat;

import com.microjainslee.ra.jss7.StpTransitProfile;
import et.elisa.stp.config.StpTransitConfig;
import et.elisa.stp.config.StpTransitConfigLoader;
import org.junit.jupiter.api.Test;
import org.restcomm.protocols.ss7.config.Ss7Config;
import org.restcomm.protocols.ss7.config.Ss7ConfigLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Nextgen STP SCCP N-N relay — config + routing-table verification.
 *
 * <p>Loads {@code configs/ss7.json} (stack topology) and {@code configs/stp.json}
 * (transit/ACL posture) and proves the relay contract:</p>
 * <ul>
 *   <li>STP local PC 10, reachable peers 2 (AS-A), 3 (AS-B) and 4 (AS-IWF);</li>
 *   <li>three {@code remote} GTT rules: B's {@code 29190003/*} → DPC 3, A's
 *       {@code 29190002/*} → DPC 2, IWF's {@code 29190004/*} → DPC 4;</li>
 *   <li>transit/ACL: canRelay + removeSpc + default-deny ACL for OPC 2, 3 &amp; 4.</li>
 * </ul>
 */
class NnRelayConfigTest {

    private static Path config(String name) {
        Path direct = Path.of(name);
        if (Files.isRegularFile(direct)) {
            return direct;
        }
        Path up = Path.of("..", name);
        if (Files.isRegularFile(up)) {
            return up;
        }
        throw new AssertionError("missing config file: " + name
                + " (looked at " + direct.toAbsolutePath() + " and " + up.toAbsolutePath() + ")");
    }

    @Test
    void ss7StackTopologyIsStpRelayWithPeersAndIwf() throws IOException {
        Ss7Config cfg = Ss7ConfigLoader.load(config("configs/ss7.json"));

        assertThat(cfg.stackName()).isEqualTo("stp-relay");
        assertThat(cfg.protocols().map()).isFalse();
        assertThat(cfg.protocols().cap()).isFalse();

        assertThat(cfg.sccp().localPoints()).hasSize(1);
        Ss7Config.LocalPoint stp = cfg.sccp().localPoints().get(0);
        assertThat(stp.pc()).isEqualTo(10);
        assertThat(stp.networkId()).isZero();
        assertThat(stp.reachablePointCodes()).containsExactly(2, 3, 4);

        assertThat(cfg.sctp().links()).hasSize(3);
        assertThat(cfg.sctp().links()).extracting(Ss7Config.Link::type)
                .containsOnly("server");
        assertThat(cfg.sctp().links()).extracting(Ss7Config.Link::local)
                .containsExactlyInAnyOrder("127.0.0.1:8021", "127.0.0.1:8023", "127.0.0.1:8025");

        assertThat(cfg.m3ua().as()).hasSize(3);
        assertThat(cfg.m3ua().as()).extracting(Ss7Config.As::name)
                .containsExactlyInAnyOrder("AS-A", "AS-B", "AS-IWF");
        assertThat(cfg.m3ua().routes()).hasSize(3);
        var aRoutes = routeByVia(cfg, "AS-A");
        var bRoutes = routeByVia(cfg, "AS-B");
        var iwfRoutes = routeByVia(cfg, "AS-IWF");
        assertThat(aRoutes).hasSize(1);
        assertThat(bRoutes).hasSize(1);
        assertThat(iwfRoutes).hasSize(1);
        assertThat(aRoutes.get(0).to().dpc()).isEqualTo(2);
        assertThat(bRoutes.get(0).to().dpc()).isEqualTo(3);
        assertThat(iwfRoutes.get(0).to().dpc()).isEqualTo(4);

        assertThat(cfg.services()).hasSize(3);
        assertThat(cfg.services()).extracting(Ss7Config.Service::ssn)
                .containsExactlyInAnyOrder(8, 6, 145);
    }

    @Test
    void sccpRulesResolveBidirectionallyAndToIwf() throws IOException {
        Ss7Config cfg = Ss7ConfigLoader.load(config("configs/ss7.json"));
        List<Ss7Config.Rule> rules = cfg.sccp().routing();

        assertThat(rules).hasSize(3);
        assertThat(rules).allSatisfy(r -> {
            assertThat(r.from()).isEqualToIgnoringCase("remote");
            assertThat(r.networkId()).isZero();
        });

        // A -> B: A's UDT addressed to B's GT range resolves to DPC 3 / SSN 8.
        Ss7Config.Rule toB = resolve(rules, "291900030001");
        assertThat(toB.to().pc()).isEqualTo(3);
        assertThat(toB.to().ssn()).isEqualTo(8);

        // B -> A: B's UDT addressed to A's GT range resolves to DPC 2 / SSN 8.
        Ss7Config.Rule toA = resolve(rules, "291900020001");
        assertThat(toA.to().pc()).isEqualTo(2);
        assertThat(toA.to().ssn()).isEqualTo(8);

        // -> IWF: relayed UDT addressed to IWF's GT range resolves to DPC 4 / SSN 8.
        Ss7Config.Rule toIwf = resolve(rules, "291900040001");
        assertThat(toIwf.to().pc()).isEqualTo(4);
        assertThat(toIwf.to().ssn()).isEqualTo(8);

        assertThat(toB).isNotSameAs(toA);
    }

    @Test
    void transitProfileEnablesRelayAndAclWithIwfPeer() throws IOException {
        StpTransitConfig cfg = StpTransitConfigLoader.load(config("configs/stp.json"));

        assertThat(cfg.stackName()).isEqualTo("stp-relay");
        assertThat(cfg.ha().mode()).isEqualTo("ACTIVE_ACTIVE");
        assertThat(cfg.transit().enabled()).isTrue();
        assertThat(cfg.transit().removeSpc()).isTrue();
        assertThat(cfg.transit().maskGtInLogs()).isTrue();
        assertThat(cfg.acl().defaultAction()).isEqualTo("DROP_SILENT");
        assertThat(cfg.acl().entries()).extracting(StpTransitConfig.Acl.AclEntry::dpc)
                .containsExactly(2, 3, 4);

        StpTransitProfile profile = cfg.toRaProfile();
        assertThat(profile.transitEnabled()).isTrue();
        assertThat(profile.removeSpcOnRelay()).isTrue();
        assertThat(profile.aclEnabled()).isTrue();
        assertThat(profile.aclRules()).hasSize(3);
        assertThat(profile.aclRules()).extracting(StpTransitProfile.AclPeerRule::incomingOpc)
                .containsExactly(2, 3, 4);
        assertThat(profile.aclRules()).anySatisfy(r -> {
            assertThat(r.calledGtPrefixes()).isEqualTo(List.of("29190004*"));
            assertThat(r.allowedSsns()).containsExactlyInAnyOrder(6, 8, 145);
        });
    }

    /** Mirrors jSS7 prefix/&#42; matching: {@code AAAA/&#42;} matches any GT starting with AAAA. */
    private static Ss7Config.Rule resolve(List<Ss7Config.Rule> rules, String digits) {
        for (Ss7Config.Rule r : rules) {
            if (gtMatches(r.match().gt(), digits)) {
                return r;
            }
        }
        throw new AssertionError("no rule matches GT " + digits);
    }

    private static boolean gtMatches(String pattern, String digits) {
        String p = pattern.replace("/", "");
        if ("*".equals(p)) {
            return true;
        }
        if (p.endsWith("*")) {
            return digits.startsWith(p.substring(0, p.length() - 1));
        }
        return p.equals(digits);
    }

    private static List<Ss7Config.Route> routeByVia(Ss7Config cfg, String asName) {
        return cfg.m3ua().routes().stream().filter(r -> asName.equals(r.via())).toList();
    }
}