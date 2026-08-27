package et.elisa.stp.events;

import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.annotations.EventType;

/**
 * Active-standby HA lease lost (DESIGN §4/§5): the ISPN lease generation
 * this node held was superseded — the RA demotes its ASPs, this event only
 * informs the observe plane. Immutable; no business logic in SBBs.
 */
@EventType(name = "StpHaLeaseLost", vendor = "et.elisa.stp", version = "1.0")
public record HaLeaseLostEvent(String nodeId, long leaseGeneration, long observedAtMillis)
        implements SleeEvent {
    public HaLeaseLostEvent {
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId");
        }
        if (leaseGeneration < 0) {
            throw new IllegalArgumentException("leaseGeneration must be >= 0");
        }
    }
}
