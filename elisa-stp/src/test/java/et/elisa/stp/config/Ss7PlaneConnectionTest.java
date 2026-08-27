package et.elisa.stp.config;

import org.junit.jupiter.api.Test;
import org.restcomm.protocols.ss7.config.Ss7Config;
import org.restcomm.protocols.ss7.config.Ss7ConfigLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B3: connection-card persistence — upsert/delete one operator connection
 * (link + AS + route + GTT rule) inside a plane fragment.
 */
class Ss7PlaneConnectionTest {

    /** Outer plane owns the stack-global bits: transport scalars + local points. */
    private static final String OUTER_SEED = """
            {
              "stackName": "stp-relay",
              "sctp": { "backend": "NETTY_KERNEL", "links": [] },
              "m3ua": { "as": [], "routes": [] },
              "sccp": {
                "localPoints": [
                  { "pc": 10, "networkIndicator": "national", "networkId": 0 }
                ],
                "routing": []
              },
              "services": [ { "name": "relay", "ssn": 8, "protocol": "tcap" } ]
            }
            """;

    /** Inner plane carries only its own links / AS / routes / GTT rules. */
    private static final String INNER_SEED = """
            {
              "sctp": { "links": [] },
              "m3ua": { "as": [], "routes": [] },
              "sccp": { "routing": [] }
            }
            """;

    private static Path tmpPlane(String body) throws Exception {
        Path f = Files.createTempFile("plane", ".json");
        Files.writeString(f, body);
        return f;
    }

    @Test
    void saveAddsFullConnectionAndMergedParses() throws Exception {
        Ss7PlaneStore store = storeWith();
        var r = store.saveConnection(Ss7PlaneStore.PLANE_INNER, Ss7PlaneStore.ConnectionSpec.of(
                "L-C", "127.0.0.1", 8025, "127.0.0.1", 8026,
                "loadshare", 0, 203, 0, "2519/*", 251));
        assertThat(r.ok()).isTrue();

        List<Map<String, Object>> conns = store.listConnections(Ss7PlaneStore.PLANE_INNER);
        assertThat(conns).hasSize(1);
        Map<String, Object> c = conns.get(0);
        assertThat(c.get("name")).isEqualTo("L-C");
        assertThat(c.get("asName")).isEqualTo("AS-L-C");
        assertThat(c.get("dpc")).isEqualTo(203);
        assertThat(c.get("gttPattern")).isEqualTo("2519/*");
        assertThat(c.get("gttToSsn")).isEqualTo(251);

        // merged document must parse with the real loader
        String merged = Ss7PlaneStore.merge(store.plane(Ss7PlaneStore.PLANE_OUTER),
                store.plane(Ss7PlaneStore.PLANE_INNER));
        Ss7Config cfg = Ss7ConfigLoader.parse(merged);
        assertThat(cfg.sctp().links()).extracting(Ss7Config.Link::name).containsExactly("L-C");
        assertThat(cfg.sccp().routing()).hasSize(1);
    }

    @Test
    void renameUpsertsInsteadOfDuplicating() throws Exception {
        Ss7PlaneStore store = storeWith();
        store.saveConnection(Ss7PlaneStore.PLANE_INNER, Ss7PlaneStore.ConnectionSpec.of(
                "L-C", "127.0.0.1", 8025, "127.0.0.1", 8026, "loadshare", 0, 203, 0, null, null));

        var renamed = new Ss7PlaneStore.ConnectionSpec(
                "L-D", "sctp", "127.0.0.1", 8025, "127.0.0.1", 8026,
                "AS-L-D", "loadshare", 0, 204, null, 0, null, null, "L-C");
        var r = store.saveConnection(Ss7PlaneStore.PLANE_INNER, renamed);
        assertThat(r.ok()).isTrue();

        List<Map<String, Object>> conns = store.listConnections(Ss7PlaneStore.PLANE_INNER);
        assertThat(conns).hasSize(1);
        assertThat(conns.get(0).get("name")).isEqualTo("L-D");
        assertThat(conns.get(0).get("dpc")).isEqualTo(204);
    }

    @Test
    void deleteRemovesEverything() throws Exception {
        Ss7PlaneStore store = storeWith();
        store.saveConnection(Ss7PlaneStore.PLANE_INNER, Ss7PlaneStore.ConnectionSpec.of(
                "L-C", "127.0.0.1", 8025, "127.0.0.1", 8026, "loadshare", 0, 203, 0, "2519/*", 251));
        var r = store.deleteConnection(Ss7PlaneStore.PLANE_INNER, "L-C");
        assertThat(r.ok()).isTrue();
        assertThat(store.listConnections(Ss7PlaneStore.PLANE_INNER)).isEmpty();

        String merged = Ss7PlaneStore.merge(store.plane(Ss7PlaneStore.PLANE_OUTER),
                store.plane(Ss7PlaneStore.PLANE_INNER));
        Ss7Config cfg = Ss7ConfigLoader.parse(merged);
        assertThat(cfg.sctp().links()).isEmpty();
        assertThat(cfg.m3ua().routes()).isEmpty();
        assertThat(cfg.sccp().routing()).isEmpty();
    }

    @Test
    void invalidSpecIsRejected() throws Exception {
        Ss7PlaneStore store = storeWith();
        assertThat(store.saveConnection(Ss7PlaneStore.PLANE_INNER,
                Ss7PlaneStore.ConnectionSpec.of("bad name!", "h", 1, "h", 2, null, 0, 3, 0, null, null))
                .ok()).isFalse();
        assertThat(store.saveConnection(Ss7PlaneStore.PLANE_INNER,
                Ss7PlaneStore.ConnectionSpec.of("L-Z", "h", 0, "h", 2, null, 0, 3, 0, null, null))
                .ok()).isFalse();
        // GTT pattern without SSN is rejected
        assertThat(store.saveConnection(Ss7PlaneStore.PLANE_INNER,
                Ss7PlaneStore.ConnectionSpec.of("L-Z", "h", 1, "h", 2, null, 0, 3, 0, "2519/*", null))
                .ok()).isFalse();
    }

    private static Ss7PlaneStore storeWith() throws Exception {
        Path outer = tmpPlane(OUTER_SEED);
        Path inner = tmpPlane(INNER_SEED);
        Path merged = Files.createTempFile("merged", ".json");
        return new Ss7PlaneStore(outer.toString(), inner.toString(), merged.toString());
    }
}
