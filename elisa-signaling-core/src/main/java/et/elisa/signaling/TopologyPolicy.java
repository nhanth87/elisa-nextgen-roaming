package et.elisa.signaling;

public record TopologyPolicy(boolean enabled, ThMode defaultMode) {

    public static final TopologyPolicy DISABLED = new TopologyPolicy(false, ThMode.OFF);

    public static TopologyPolicy of(ThMode mode) {
        return new TopologyPolicy(mode != ThMode.OFF, mode);
    }
}
