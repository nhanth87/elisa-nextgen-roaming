package et.elisa.stp.ha;

import static org.assertj.core.api.Assertions.assertThat;

import et.elisa.stp.gtt.GttHarness;
import et.elisa.stp.gtt.GttResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Verifies GTT rules resolve identically regardless of which STP node processes
 * the message — the core correctness requirement for active-active HA (DESIGN
 * §9.3/§9.5).
 *
 * <p>In a stateless relay, both nodes share the same GTT rule table (loaded
 * from the same {@code gtt-fixtures.json}). This test proves that any called GT
 * produces the same DPC+SSN translation on every instantiation of the harness,
 * simulating two independent STP nodes with identical config.</p>
 */
class StpGttConsistencyTest {

    private static GttHarness loadHarness() throws IOException {
        try (InputStream in = StpGttConsistencyTest.class.getResourceAsStream("/gtt-fixtures.json")) {
            assertThat(in).as("gtt-fixtures.json on the test classpath").isNotNull();
            return GttHarness.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /** Two harness instances = two STP nodes with identical GTT rule tables. */
    private record TwoNodes(GttHarness node1, GttHarness node2) {
        static TwoNodes create() throws IOException {
            return new TwoNodes(loadHarness(), loadHarness());
        }
    }

    @Test
    void ussdRangeResolvesIdenticallyOnBothNodes() throws IOException {
        TwoNodes nodes = TwoNodes.create();
        GttResult r1 = nodes.node1().resolve("25199876543", 0);
        GttResult r2 = nodes.node2().resolve("25199876543", 0);

        assertThat(r1.resolved()).isTrue();
        assertThat(r2.resolved()).isTrue();
        assertThat(r1.dpc()).isEqualTo(r2.dpc());
        assertThat(r1.ssn()).isEqualTo(r2.ssn());
        assertThat(r1.service()).isEqualTo(r2.service());
        assertThat(r1.ruleId()).isEqualTo(r2.ruleId());
    }

    @Test
    void smscRangeResolvesIdenticallyOnBothNodes() throws IOException {
        TwoNodes nodes = TwoNodes.create();
        GttResult r1 = nodes.node1().resolve("251100000007", 0);
        GttResult r2 = nodes.node2().resolve("251100000007", 0);

        assertThat(r1.dpc()).isEqualTo(r2.dpc());
        assertThat(r1.ssn()).isEqualTo(r2.ssn());
        assertThat(r1.service()).isEqualTo("SMSC");
    }

    @Test
    void gmlcRangeResolvesIdenticallyOnBothNodes() throws IOException {
        TwoNodes nodes = TwoNodes.create();
        GttResult r1 = nodes.node1().resolve("251255512345", 0);
        GttResult r2 = nodes.node2().resolve("251255512345", 0);

        assertThat(r1.dpc()).isEqualTo(r2.dpc());
        assertThat(r1.ssn()).isEqualTo(r2.ssn());
        assertThat(r1.service()).isEqualTo("GMLC");
    }

    @Test
    void exactShortcodeResolvesIdenticallyOnBothNodes() throws IOException {
        TwoNodes nodes = TwoNodes.create();
        GttResult r1 = nodes.node1().resolve("2519123456", 0);
        GttResult r2 = nodes.node2().resolve("2519123456", 0);

        assertThat(r1.ruleId()).isEqualTo(r2.ruleId());
        assertThat(r1.dpc()).isEqualTo(r2.dpc());
    }

    @Test
    void negativeGtNeverResolvesOnEitherNode() throws IOException {
        TwoNodes nodes = TwoNodes.create();
        for (String gt : nodes.node1().fixtures().negativeGts()) {
            GttResult r1 = nodes.node1().resolve(gt, 0);
            GttResult r2 = nodes.node2().resolve(gt, 0);
            assertThat(r1.resolved()).as("node1: %s unresolved", gt).isFalse();
            assertThat(r2.resolved()).as("node2: %s unresolved", gt).isFalse();
        }
    }

    @Test
    void multipleCallsToSameGtAreIdempotentAcrossNodes() throws IOException {
        TwoNodes nodes = TwoNodes.create();
        String gt = "25195551234";
        for (int i = 0; i < 10; i++) {
            GttResult r1 = nodes.node1().resolve(gt, 0);
            GttResult r2 = nodes.node2().resolve(gt, 0);
            assertThat(r1.dpc()).isEqualTo(r2.dpc());
            assertThat(r1.ssn()).isEqualTo(r2.ssn());
        }
    }

    @Test
    void unmatchedGtRemainsUnresolvedOnBothNodes() throws IOException {
        TwoNodes nodes = TwoNodes.create();
        GttResult r1 = nodes.node1().resolve("9999999999", 0);
        GttResult r2 = nodes.node2().resolve("9999999999", 0);

        assertThat(r1.resolved()).isFalse();
        assertThat(r2.resolved()).isFalse();
    }

    @Test
    void networkIdPartitionProducesSameResultOnBothNodes() throws IOException {
        TwoNodes nodes = TwoNodes.create();
        GttResult r1 = nodes.node1().resolve("25199876543", 1);
        GttResult r2 = nodes.node2().resolve("25199876543", 1);

        assertThat(r1.resolved()).isFalse();
        assertThat(r2.resolved()).isFalse();
    }
}
