package et.elisa.stp.events;

import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.annotations.EventType;

/**
 * Transit-plane reject observed on ingress (DESIGN §5): an incoming message
 * was dropped before relay. {@code reason} distinguishes the OAM causes —
 * {@code ACL_DENY} (incoming ACL / SS7-firewall-lite hit) and
 * {@code CONGESTION} (importance-based overload control drop, DESIGN §10.2
 * P1+P2). Immutable; observe-only consumers. GT digits must never appear
 * here unmasked (topology hiding, DESIGN §1) — carry masked detail only.
 */
@EventType(name = "StpTransitRejected", vendor = "et.elisa.stp", version = "1.0")
public record TransitRejectedEvent(int opc, String reason, String detail, long observedAtMillis)
        implements SleeEvent {
    public TransitRejectedEvent {
        if (opc <= 0) {
            throw new IllegalArgumentException("opc must be > 0");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason");
        }
    }
}
