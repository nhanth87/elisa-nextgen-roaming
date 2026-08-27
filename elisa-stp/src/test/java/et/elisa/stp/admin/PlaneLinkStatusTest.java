package et.elisa.stp.admin;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B3 observability: per-plane join between the editable SS7 plane fragments and
 * the live {@code Ss7LinkStatusSnapshot.capture()} rows.
 */
class PlaneLinkStatusTest {

    private static final String OUTER_FRAGMENT = """
            {
              "sctp": { "links": [ { "name": "L-A", "type": "server",
                                     "local": "127.0.0.1:8021", "peer": "127.0.0.1:8022" } ] },
              "m3ua": { "as": [ { "name": "AS-A", "mode": "loadshare" } ] }
            }
            """;

    private static final String INNER_FRAGMENT = """
            {
              "sctp": { "links": [ { "name": "L-B", "type": "server",
                                     "local": "127.0.0.1:8023", "peer": "127.0.0.1:8024" } ] },
              "m3ua": { "as": [ { "name": "AS-B", "mode": "loadshare" } ] }
            }
            """;

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    /** Mirrors Ss7LinkStatusSnapshot.capture() shape for a healthy stack. */
    private static Map<String, Object> capture() {
        return Map.of(
                "servers", List.of(row("name", "L-A-srv", "local", "127.0.0.1:8021", "state", "LISTEN")),
                "associations", List.of(
                        row("name", "L-A", "peer", "127.0.0.1:8022", "connected", true, "state", "UP"),
                        row("name", "L-B", "peer", "127.0.0.1:8024", "connected", false, "state", "DOWN")),
                "applicationServers", List.of(
                        row("name", "AS-A", "state", "AS-ACTIVE"),
                        row("name", "AS-B", "state", "AS-DOWN")));
    }

    @Test
    void outerPlaneAllUpIsLive() {
        PlaneLinkStatus.PlaneView v = PlaneLinkStatus.view("outer", OUTER_FRAGMENT, capture());
        assertThat(v.keys().get("ss7.outer.live")).isEqualTo(true);
        assertThat(v.keys().get("ss7.outer.linksUp")).isEqualTo(1L);
        assertThat(v.keys().get("ss7.outer.linksTotal")).isEqualTo(1L);
        assertThat(v.links()).hasSize(1);
        assertThat(v.links().get(0).up()).isTrue();
        assertThat(v.links().get(0).peer()).isEqualTo("127.0.0.1:8022");
        assertThat(v.ases().get(0).active()).isTrue();
        assertThat(v.ases().get(0).state()).isEqualTo("AS-ACTIVE");
        assertThat((String) v.keys().get("ss7.outer.detail")).contains("links=1/1");
    }

    @Test
    void innerPlaneDownLinkIsNotLive() {
        PlaneLinkStatus.PlaneView v = PlaneLinkStatus.view("inner", INNER_FRAGMENT, capture());
        assertThat(v.keys().get("ss7.inner.live")).isEqualTo(false);
        assertThat(v.keys().get("ss7.inner.linksUp")).isEqualTo(0L);
        assertThat(v.links().get(0).state()).isEqualTo("DOWN");
        // AS-DOWN must not count as active (contains-ACTIVE but not PENDING trap avoided)
        assertThat(v.ases().get(0).active()).isFalse();
        assertThat(v.ases().get(0).state()).isEqualTo("AS-DOWN");
    }

    @Test
    void serverOnlyLinkFallsBackToListenRow() {
        String fragment = """
                { "sctp": { "links": [ { "name": "L-X" } ] }, "m3ua": { "as": [] } }
                """;
        PlaneLinkStatus.PlaneView v = PlaneLinkStatus.view("inner", fragment, capture());
        assertThat(v.links()).hasSize(1);
        // L-X has neither association nor "<name>-srv" server → DOWN
        assertThat(v.links().get(0).up()).isFalse();
        assertThat(v.links().get(0).state()).isEqualTo("DOWN");
    }

    @Test
    void blankAndBrokenFragmentsYieldEmptyView() {
        for (String bad : new String[]{null, "", "   ", "{ not json", "[]"}) {
            PlaneLinkStatus.PlaneView v = PlaneLinkStatus.view("outer", bad, capture());
            assertThat(v.links()).isEmpty();
            assertThat(v.ases()).isEmpty();
            assertThat(v.keys().get("ss7.outer.live")).isEqualTo(false);
        }
    }

    @Test
    void nullCaptureNeverThrows() {
        PlaneLinkStatus.PlaneView v = PlaneLinkStatus.view("outer", OUTER_FRAGMENT, null);
        assertThat(v.links().get(0).up()).isFalse();
        assertThat(v.ases().get(0).active()).isFalse();
    }
}
