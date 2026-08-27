package et.elisa.stp.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.restcomm.protocols.ss7.config.Ss7Config;
import org.restcomm.protocols.ss7.config.Ss7ConfigLoader;

/**
 * Nextgen STP SS7 outer/inner plane merge — verifies the admin save path fuses
 * the two editable fragment documents into one loadable {@code configs/ss7.json}
 * without dropping any plane's topology.
 */
class Ss7PlaneStoreTest {

    private static String outerFragment() {
        return """
                {
                  "stackName": "stp-relay",
                  "sctp": {
                    "backend": "FSTACK_DPDK",
                    "links": [
                      { "name": "OUT-1", "type": "server", "local": "127.0.0.1:8021", "peer": "127.0.0.1:8022" }
                    ]
                  },
                  "m3ua": {
                    "as": [ { "name": "AS-OUT", "functionality": "ipsp", "ipsp": "server", "links": ["OUT-1"] } ],
                    "routes": [ { "to": { "dpc": 2, "opc": 10 }, "via": "AS-OUT" } ]
                  },
                  "sccp": {
                    "localPoints": [ { "pc": 10, "networkId": 0, "reachablePointCodes": [2] } ],
                    "routing": [
                      { "from": "remote", "networkId": 0, "mask": "K", "match": { "gt": "29190003/*" }, "to": { "pc": 2, "ssn": 8 } }
                    ]
                  },
                  "services": [ { "name": "relay", "ssn": 8, "protocol": "tcap" } ]
                }
                """;
    }

    private static String innerFragment() {
        return """
                {
                  "stackName": "stp-relay",
                  "sctp": {
                    "links": [
                      { "name": "IN-1", "type": "server", "local": "127.0.0.1:8023", "peer": "127.0.0.1:8024" }
                    ]
                  },
                  "m3ua": {
                    "as": [ { "name": "AS-IN", "functionality": "ipsp", "ipsp": "server", "links": ["IN-1"] } ],
                    "routes": [ { "to": { "dpc": 3, "opc": 10 }, "via": "AS-IN" } ]
                  },
                  "sccp": {
                    "localPoints": [ { "pc": 10, "networkId": 1, "reachablePointCodes": [3] } ],
                    "routing": [
                      { "from": "remote", "networkId": 1, "mask": "K", "match": { "gt": "29190002/*" }, "to": { "pc": 3, "ssn": 8 } }
                    ]
                  },
                  "services": [ { "name": "ussd", "ssn": 12, "protocol": "tcap" } ]
                }
                """;
    }

    @Test
    void mergeConcatenatesBothPlanesAndParses() {
        String merged = Ss7PlaneStore.merge(outerFragment(), innerFragment());
        Ss7Config cfg = Ss7ConfigLoader.parse(merged);

        assertThat(cfg.stackName()).isEqualTo("stp-relay");
        assertThat(cfg.protocols().map()).isFalse();
        assertThat(cfg.protocols().cap()).isFalse();

        assertThat(cfg.sctp().links()).extracting(Ss7Config.Link::name)
                .containsExactly("OUT-1", "IN-1");
        assertThat(cfg.sctp().backend()).isEqualTo("FSTACK_DPDK");

        assertThat(cfg.m3ua().as()).extracting(Ss7Config.As::name)
                .containsExactly("AS-OUT", "AS-IN");
        assertThat(cfg.m3ua().routes()).hasSize(2);

        assertThat(cfg.sccp().localPoints()).hasSize(2);
        assertThat(cfg.sccp().localPoints()).extracting(Ss7Config.LocalPoint::networkId)
                .containsExactly(0, 1);
        assertThat(cfg.sccp().routing()).hasSize(2);

        assertThat(cfg.services()).extracting(Ss7Config.Service::name)
                .containsExactly("relay", "ussd");
    }

    @Test
    void mergeIsOrderedOuterFirst() {
        String merged = Ss7PlaneStore.merge(outerFragment(), innerFragment());
        assertThat(merged.indexOf("OUT-1")).isLessThan(merged.indexOf("IN-1"));
        assertThat(merged.indexOf("AS-OUT")).isLessThan(merged.indexOf("AS-IN"));
    }

    @Test
    void duplicateNamesAcrossPlanesAreRejected() {
        String good = Ss7PlaneStore.findDuplicateNames(
                Ss7PlaneStore.merge(outerFragment(), innerFragment()));
        assertThat(good).isNull();

        String dupLink = """
                {
                  "sctp": { "links": [ { "name": "OUT-1", "type": "server",
                                        "local": "10.0.0.9:9021", "peer": "10.0.0.2:8022" } ] }
                }
                """;
        String bad = Ss7PlaneStore.findDuplicateNames(
                Ss7PlaneStore.merge(outerFragment(), dupLink));
        assertThat(bad).contains("duplicate sctp.links name").contains("OUT-1");

        String dupAs = """
                {
                  "m3ua": { "as": [ { "name": "AS-OUT", "mode": "loadshare" } ] }
                }
                """;
        bad = Ss7PlaneStore.findDuplicateNames(Ss7PlaneStore.merge(outerFragment(), dupAs));
        assertThat(bad).contains("duplicate m3ua.as name").contains("AS-OUT");
    }
}