package et.elisa.stp.events;

import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.annotations.EventType;

/**
 * M3UA ASP state transition observed at the STP edge (DESIGN §5 OAM plane).
 * Fired by ra-jss7 when a peer ASP moves between DOWN / INACTIVE / ACTIVE.
 * Immutable; observe-only consumers (no socket, no relay logic).
 */
@EventType(name = "StpAspStateChange", vendor = "et.elisa.stp", version = "1.0")
public record AspStateChangeEvent(String aspName, String previousState, String currentState,
                                  long observedAtMillis)
        implements SleeEvent {
    public AspStateChangeEvent {
        if (aspName == null || aspName.isBlank()) {
            throw new IllegalArgumentException("aspName");
        }
        if (currentState == null || currentState.isBlank()) {
            throw new IllegalArgumentException("currentState");
        }
    }
}
