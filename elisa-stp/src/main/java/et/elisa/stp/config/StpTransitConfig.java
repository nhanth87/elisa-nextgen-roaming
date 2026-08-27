package et.elisa.stp.config;

import com.microjainslee.ra.jss7.StpTransitProfile;

/**
 * STP transit-plane config loaded from {@code configs/stp.json}.
 *
 * <p>Pure JSON-facing model; {@link #toRaProfile()} translates it into the
 * ra-jss7 {@link StpTransitProfile} the adaptor applies to a RUNNING jSS7
 * stack (canRelay / removeSpc / incoming ACL / HA mode).</p>
 *
 * <p>Global-title translation itself lives in the jSS7 stack JSON
 * ({@code sccp.routing} GT rules — see {@code org.restcomm.protocols.ss7.config.Ss7Config.Rule});
 * this record only carries the STP transit/HA/ACL posture on top of it.</p>
 */
public record StpTransitConfig(
        String stackName,
        Ha ha,
        Transit transit,
        Acl acl) {

    /**
     * HA deployment mode.
     *
     * @param mode             {@code ACTIVE_ACTIVE} or {@code ACTIVE_STANDBY}
     * @param nodeId           unique node identity (OTID partitioning key)
     * @param dialogIdRangeStart inclusive TCAP OTID range start (0 = jSS7 default)
     * @param dialogIdRangeEnd   inclusive TCAP OTID range end
     * @param peers            sibling node ids for failover awareness
     */
    public record Ha(String mode, String nodeId,
                     long dialogIdRangeStart, long dialogIdRangeEnd,
                     java.util.List<String> peers) {
        public Ha {
            if (mode == null || mode.isBlank()) {
                throw new IllegalArgumentException("stp ha.mode required (ACTIVE_ACTIVE|ACTIVE_STANDBY)");
            }
            mode = mode.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_');
            if (!mode.equals("ACTIVE_ACTIVE") && !mode.equals("ACTIVE_STANDBY")) {
                throw new IllegalArgumentException("stp ha.mode invalid: " + mode);
            }
            nodeId = nodeId == null || nodeId.isBlank() ? "stp-1" : nodeId.trim();
            peers = peers == null ? java.util.List.of() : java.util.List.copyOf(peers);
            if (dialogIdRangeStart < 0 || dialogIdRangeEnd < 0) {
                throw new IllegalArgumentException("stp ha.dialogIdRange must be >= 0");
            }
            if (dialogIdRangeStart > 0 && dialogIdRangeEnd < dialogIdRangeStart) {
                throw new IllegalArgumentException("stp ha.dialogIdRangeEnd < dialogIdRangeStart");
            }
        }

        public boolean activeActive() { return "ACTIVE_ACTIVE".equals(mode); }

        public StpTransitProfile.HaMode raHaMode() {
            return activeActive()
                    ? StpTransitProfile.HaMode.ACTIVE_ACTIVE
                    : StpTransitProfile.HaMode.ACTIVE_STANDBY;
        }
    }

    /**
     * Transit behaviour.
     *
     * @param enabled      master switch: SCCP canRelay (relay non-local DPC traffic)
     * @param removeSpc    topology hiding — peers never see internal point codes
     * @param maskGtInLogs mask GT digits in RA log lines
     */
    public record Transit(boolean enabled, boolean removeSpc, boolean maskGtInLogs) {
        public static Transit defaults() {
            return new Transit(true, true, true);
        }
    }

    /**
     * Transit access-control list evaluated on ingress (SS7-firewall-lite).
     *
     * @param defaultAction {@code ALLOW} or {@code DROP_SILENT} (silent drop, no response)
     * @param entries       per-peer rules; ALLOW entries whitelist an incoming OPC
     */
    public record Acl(String defaultAction, java.util.List<AclEntry> entries) {
        public Acl {
            defaultAction = defaultAction == null || defaultAction.isBlank()
                    ? "DROP_SILENT" : defaultAction.trim().toUpperCase(java.util.Locale.ROOT);
            if (!defaultAction.equals("ALLOW") && !defaultAction.equals("DROP_SILENT")) {
                throw new IllegalArgumentException("stp acl.defaultAction invalid: " + defaultAction);
            }
            entries = entries == null ? java.util.List.of() : java.util.List.copyOf(entries);
        }

        /**
         * @param action ALLOW or DROP_SILENT
         * @param dpc    peer point code (OPC seen on ingress)
         * @param gt     allowed called-GT prefix; trailing {@code *} wildcard
         * @param ssns   allowed SSNs from this peer (empty = unrestricted)
         */
        public record AclEntry(String action, Integer dpc, String gt, java.util.Set<Integer> ssns) {
            public AclEntry {
                if (action == null || action.isBlank()) {
                    throw new IllegalArgumentException("stp acl entry action required");
                }
                action = action.trim().toUpperCase(java.util.Locale.ROOT);
                if (!action.equals("ALLOW") && !action.equals("DROP_SILENT")) {
                    throw new IllegalArgumentException("stp acl entry action invalid: " + action);
                }
                if (dpc == null || dpc <= 0) {
                    throw new IllegalArgumentException("stp acl entry dpc required (> 0)");
                }
                ssns = ssns == null ? java.util.Set.of() : java.util.Set.copyOf(ssns);
            }
        }

        public boolean allowByDefault() { return "ALLOW".equals(defaultAction); }
    }

    /**
     * Translate to the ra-jss7 profile. DROP_SILENT posture is enforced by
     * default-deny (a blocked peer simply has no rule); only ALLOW entries
     * become {@link StpTransitProfile.AclPeerRule}s. A DROP_SILENT ACL with
     * zero ALLOW rules is treated as not-yet-configured (no enforcement) —
     * never an accidental black-hole.
     */
    public StpTransitProfile toRaProfile() {
        Transit t = transit == null ? Transit.defaults() : transit;
        Acl a = acl == null ? new Acl(null, null) : acl;
        java.util.List<StpTransitProfile.AclPeerRule> rules = new java.util.ArrayList<>();
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (Acl.AclEntry e : a.entries()) {
            if (!"ALLOW".equals(e.action())) continue;
            if (!seen.add(e.dpc())) {
                throw new IllegalArgumentException("stp acl: duplicate entry for dpc=" + e.dpc());
            }
            rules.add(new StpTransitProfile.AclPeerRule(
                    e.dpc(),
                    "peer-" + e.dpc(),
                    e.gt() == null || e.gt().isBlank()
                            ? java.util.List.of() : java.util.List.of(e.gt()),
                    e.ssns()));
        }
        boolean aclEnabled = !a.allowByDefault() && !rules.isEmpty();
        StpTransitProfile p = new StpTransitProfile(
                t.enabled(),
                t.removeSpc(),
                ha == null ? StpTransitProfile.HaMode.ACTIVE_ACTIVE : ha.raHaMode(),
                t.maskGtInLogs(),
                aclEnabled,
                /* defaultDeny */ true,
                rules);
        p.validate();
        return p;
    }
}
