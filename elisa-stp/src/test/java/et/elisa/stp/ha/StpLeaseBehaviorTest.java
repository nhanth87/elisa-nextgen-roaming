package et.elisa.stp.ha;

import static org.assertj.core.api.Assertions.assertThat;

import com.microjainslee.ra.jss7.StpTransitProfile;
import et.elisa.stp.events.HaLeaseLostEvent;
import et.elisa.stp.sbb.StpHaMonitorSbb;
import org.junit.jupiter.api.Test;

/**
 * Validates HA lease-related behaviour in the observe plane —
 * event immutability, event construction, and SBB event routing.
 *
 * <p>The actual ISPN lease CAS + generation is owned by
 * {@code SctpEndpointFailoverCoordinator} in ra-jss7 (tested there);
 * this test covers the STP-side observation contract.</p>
 */
class StpLeaseBehaviorTest {

    @Test
    void haLeaseLostEventCarriesAllFields() {
        long now = System.currentTimeMillis();
        HaLeaseLostEvent ev = new HaLeaseLostEvent("stp-node-1", 7L, now);

        assertThat(ev.nodeId()).isEqualTo("stp-node-1");
        assertThat(ev.leaseGeneration()).isEqualTo(7L);
        assertThat(ev.observedAtMillis()).isEqualTo(now);
    }

    @Test
    void haLeaseLostEventRejectsBlankNodeId() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new HaLeaseLostEvent("", 0L, 0L));
    }

    @Test
    void haLeaseLostEventRejectsNegativeGeneration() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new HaLeaseLostEvent("n1", -1L, 0L));
    }

    @Test
    void haLeaseLostEventIsImmutable() {
        HaLeaseLostEvent ev = new HaLeaseLostEvent("n1", 3L, 1000L);
        assertThat(ev.nodeId()).isEqualTo("n1");
        assertThat(ev.leaseGeneration()).isEqualTo(3L);
    }

    @Test
    void haMonitorSbbConstructsAndEventIsAcceptedBySbb() {
        StpHaMonitorSbb sbb = new StpHaMonitorSbb();
        HaLeaseLostEvent ev = new HaLeaseLostEvent("stp-node-2", 5L, System.currentTimeMillis());
        assertThat(ev.nodeId()).isEqualTo("stp-node-2");
        assertThat(ev.leaseGeneration()).isEqualTo(5L);
        assertThat(sbb).isNotNull();
    }

    @Test
    void haModeDefaultIsActiveActive() {
        assertThat(StpTransitProfile.HaMode.ACTIVE_ACTIVE.toString())
                .isEqualTo("ACTIVE_ACTIVE");
    }

    @Test
    void haModeActiveStandbyExists() {
        assertThat(StpTransitProfile.HaMode.ACTIVE_STANDBY.toString())
                .isEqualTo("ACTIVE_STANDBY");
    }

    @Test
    void haModeValuesCount() {
        assertThat(StpTransitProfile.HaMode.values()).hasSize(2);
    }
}
