package et.elisa.fabric;

import java.util.EnumSet;
import java.util.Set;

import org.infinispan.configuration.cache.CacheMode;

/**
 * IR.88 shared control-plane cache inventory (design §3). One entry per
 * Infinispan cache shared by STP + IWF + DRA. Every cache has a single
 * writer role set ({@link #owners()}) and a {@link CacheMode}: REPL_SYNC for
 * small/deterministic control data, DIST_SYNC for hot/anchor keys.
 *
 * <p>Invariants (design §3 / §8 guardrails): one-owner-per-cache; no
 * per-message payload replication — fabric is control-plane only; values are
 * JDK-only maps (marshalling allow-list {@code java.*}).</p>
 */
public enum ElisaFabricCache {

    /** {realm → [host,port,appId[],transport]} IR.21-like static partner table. */
    PEER_TOPOLOGY("elisa/peer-topology", CacheMode.REPL_SYNC, FabricRole.DRA),
    /** {destRealm/appId → group,priority,thMode}. */
    ROUTE_POLICY("elisa/route-policy", CacheMode.REPL_SYNC, FabricRole.DRA),
    /** {internalOriginHost→publicHost} + {publicHost→internalHost} 2 chiều. */
    TH_MAP("elisa/th-map", CacheMode.REPL_SYNC, FabricRole.DRA),
    /** {gtPrefix→PC,SSN} public-view topology hiding cho legacy SS7. */
    GTT_PUBLIC("elisa/gtt-public", CacheMode.REPL_SYNC, FabricRole.STP),
    /** {peerId→READY/WARN/DOWN} peer-truth law (SCTP up ∧ CEA OPEN). */
    PEER_STATE("elisa/peer-state", CacheMode.DIST_SYNC, FabricRole.STP, FabricRole.DRA),
    /** {imsi → origCtx{ingressPeer,publicHost,dialOwnerNodeId,mapOp,sessionId}}. */
    IMSI_CONTEXT("elisa/imsi-context", CacheMode.DIST_SYNC, FabricRole.IWF),
    /** {dialogId|sessionId|hbh → imsi} cross-protocol correlation. */
    DIALOG_BIND("elisa/dialog-bind", CacheMode.DIST_SYNC, FabricRole.IWF),
    /** validated config vé (DRA SoT) + stp ss7.json, REPL to all. */
    CONFIG("elisa/config", CacheMode.REPL_SYNC, FabricRole.DRA, FabricRole.STP),
    /** SCTP endpoint fences active-standby (STP HA). */
    LEASES("elisa/leases", CacheMode.DIST_SYNC, FabricRole.STP);

    private final String name;
    private final CacheMode mode;
    private final Set<FabricRole> owners;

    ElisaFabricCache(String name, CacheMode mode, FabricRole... owners) {
        this.name = name;
        this.mode = mode;
        this.owners = owners.length == 0
                ? Set.of()
                : EnumSet.copyOf(java.util.List.of(owners));
    }

    public String cacheName() {
        return name;
    }

    /** Design intent when the cluster transport is up. */
    public CacheMode clusterMode() {
        return mode;
    }

    /** Writer roles — every other role may only get/list. */
    public Set<FabricRole> owners() {
        return owners;
    }

    public boolean writableBy(FabricRole role) {
        return owners.contains(role);
    }
}