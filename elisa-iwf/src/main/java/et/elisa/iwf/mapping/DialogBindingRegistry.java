package et.elisa.iwf.mapping;

import et.elisa.iwf.telemetry.IwfKpi;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * IMSI → active dialog/session binding — the bridge between the Diameter
 * and MAP sides of the IWF.
 *
 * <p><b>On outbound (MAP→DIA):</b> when the IWF sends a ULR/AIR/PUR/NOR
 * carrying an IMSI, the binding is created with the Diameter session-id and
 * (when the MAP leg lands) the TCAP dialog-id. One binding per IMSI per
 * node; a new outbound for the same IMSI replaces the previous binding.
 *
 * <p><b>On inbound (DIA→MAP):</b> when a server-initiated CLR/IDR/DSR/NOR
 * arrives with an IMSI, the registry is consulted to find the active
 * Diameter session-id (for answer correlation) and MAP dialog-id (for
 * routing the MAP invoke).
 *
 * <p><b>Thread safety:</b> ConcurrentHashMap guarantees per-operation
 * atomicity; entry-level synchronisation is not needed because each IMSI
 * has exactly one writer at any time (lesson: one active dialog per
 * IMSI per node).
 *
 * <p><b>TTL:</b> entries are evicted after {@link #TTL_MILLIS} to avoid
 * stale bindings accumulating after node failover.
 *
 * @see IwfEngine the single dispatch entry point
 */
public final class DialogBindingRegistry {

    static final long TTL_MILLIS = 3_600_000L; // 1 hour

    /**
     * One active binding for an IMSI. Immutable — each new outbound
     * replaces the entry wholesale.
     */
    public record Binding(String imsi,
                           String diameterSessionId,
                           Long diameterHopByHopId,
                           Long mapDialogId,
                           long createdAt) {

        public Binding {
            Objects.requireNonNull(imsi, "imsi");
        }

        public boolean expired() {
            return System.currentTimeMillis() - createdAt > TTL_MILLIS;
        }
    }

    private final ConcurrentMap<String, Binding> bindings = new ConcurrentHashMap<>();

    /**
     * Store or update a binding for {@code imsi}. Called on the outbound
     * path after the Diameter request is dispatched.
     */
    public void bind(String imsi, String diameterSessionId,
                     Long diameterHopByHopId, Long mapDialogId) {
        if (imsi == null || imsi.isBlank()) {
            return;
        }
        bindings.put(imsi.trim(), new Binding(imsi.trim(), diameterSessionId,
                diameterHopByHopId, mapDialogId, System.currentTimeMillis()));
        IwfKpi.binding("bind");
    }

    /**
     * Look up the active binding for {@code imsi}. Returns empty if no
     * binding exists or the entry has expired.
     */
    public Optional<Binding> lookup(String imsi) {
        if (imsi == null || imsi.isBlank()) {
            return Optional.empty();
        }
        Binding b = bindings.get(imsi.trim());
        if (b == null || b.expired()) {
            if (b != null) {
                bindings.remove(imsi.trim(), b);
            }
            return Optional.empty();
        }
        return Optional.of(b);
    }

    /** Remove a binding (e.g. after MAP dialog ends or purge). */
    public void unbind(String imsi) {
        if (imsi != null) {
            bindings.remove(imsi.trim());
            IwfKpi.binding("unbind");
        }
    }

    /** Number of active (non-expired) bindings. */
    public int size() {
        // ConcurrentHashMap.size() is O(n) but acceptable for
        // monitoring; never in hot path.
        return (int) bindings.values().stream().filter(b -> !b.expired()).count();
    }
}
