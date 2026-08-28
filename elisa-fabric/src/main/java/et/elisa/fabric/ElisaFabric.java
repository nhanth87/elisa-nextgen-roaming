package et.elisa.fabric;

import com.microjainslee.cluster.ClusterManager;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.infinispan.Cache;
import org.infinispan.configuration.cache.CacheMode;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * IR.88 shared control-plane fabric — one ClusterManager view over the cache
 * inventory (design §3). Local/offline tests keep {@code clusterEnabled=false}
 * so every cache falls back to {@link CacheMode#LOCAL} (micro-jainslee ISPN
 * rule: clustered modes require a JGroups transport).
 *
 * <p>Values are <b>JDK-only maps</b> ({@link HashMap}{@code <String,Object>}
 * with {@code String/Long/Integer/Boolean/List<String>} entries) because the
 * Infinispan {@code JavaSerializationMarshaller} allow-list covers
 * {@code java.*} — never {@code et.elisa.*} records on the wire (GMLC C7 rule,
 * lessons 26/8).</p>
 *
 * <p>One-owner gate: {@link #put} throws when the local node role is not the
 * cache owner. Validator runs fail-fast before the first write (STP G6 lesson)
 * and keeps the fabric deterministic.</p>
 */
public final class ElisaFabric {

    private static final Logger LOG = LogManager.getLogger(ElisaFabric.class);

    private final ClusterManager cluster;
    private final FabricRole nodeRole;
    private final Map<String, Cache<String, HashMap<String, Object>>> caches = new LinkedHashMap<>();

    /**
     * @param cluster  the live micro-jainslee ClusterManager (owner already started)
     * @param nodeRole the local service role (owns its caches only)
     */
    public ElisaFabric(ClusterManager cluster, FabricRole nodeRole) {
        this.cluster = Objects.requireNonNull(cluster, "cluster");
        this.nodeRole = Objects.requireNonNull(nodeRole, "nodeRole");
        for (ElisaFabricCache def : ElisaFabricCache.values()) {
            CacheMode mode = cluster.isClustered()
                    ? def.clusterMode()
                    : CacheMode.LOCAL;
            caches.put(def.cacheName(), cluster.getCache(def.cacheName(), mode));
        }
        LOG.info("ElisaFabric ready node={} role={} clusterMode={} caches={}",
                cluster.getNodeId(), nodeRole, cluster.isClustered(), caches.size());
    }

    public ClusterManager clusterManager() {
        return cluster;
    }

    public FabricRole nodeRole() {
        return nodeRole;
    }

    public boolean clustered() {
        return cluster.isClustered();
    }

    public Cache<String, HashMap<String, Object>> cache(ElisaFabricCache def) {
        return Objects.requireNonNull(caches.get(def.cacheName()), "cache " + def.cacheName());
    }

    public String nodeId() {
        return cluster.getNodeId();
    }

    // ── Reads (any role) ──

    public Map<String, Object> get(ElisaFabricCache def, String key) {
        HashMap<String, Object> value = cache(def).get(key);
        return value == null ? null : copyOf(value);
    }

    public Set<String> keys(ElisaFabricCache def) {
        return new LinkedHashSet<>(cache(def).keySet());
    }

    public int size(ElisaFabricCache def) {
        return cache(def).size();
    }

    // ── Writes (owner only; validator fail-fast) ──

    /**
     * Owner-gated, validated put. Non-owner roles throw — the fabric never
     * silently accepts a foreign-writer update.
     */
    public void put(ElisaFabricCache def, String key, Map<String, Object> values) {
        requireOwner(def);
        FabricValidator.validate(def, key, values);
        cache(def).put(key, copyOf(values));
    }

    /** Owner-gated remove. */
    public void remove(ElisaFabricCache def, String key) {
        requireOwner(def);
        cache(def).remove(key);
    }

    private void requireOwner(ElisaFabricCache def) {
        if (!def.writableBy(nodeRole)) {
            throw new IllegalStateException(
                    "fabric write gate: role " + nodeRole + " is not an owner of " + def.cacheName()
                            + " (owners=" + def.owners() + ")");
        }
    }

    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cluster.enabled", clustered());
        out.put("cluster.mode", clustered() ? "CLUSTER" : "LOCAL");
        out.put("cluster.nodeId", nodeId());
        out.put("node.role", nodeRole.toString());
        for (ElisaFabricCache def : ElisaFabricCache.values()) {
            out.put("cluster.cache." + def.cacheName().replace("elisa/", ""), size(def));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static HashMap<String, Object> copyOf(Map<String, Object> src) {
        HashMap<String, Object> copy = new HashMap<>();
        for (Map.Entry<String, Object> e : src.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Number || v instanceof Boolean || v instanceof String
                    || v instanceof java.util.List<?> list && list.stream().allMatch(
                            i -> i instanceof Number || i instanceof Boolean || i instanceof String)
                    || v == null) {
                if (v instanceof java.util.List<?> list) {
                    copy.put(e.getKey(), new java.util.ArrayList<>(list));
                } else {
                    copy.put(e.getKey(), v);
                }
            } else {
                throw new IllegalArgumentException(
                        "fabric value for '" + e.getKey() + "' must be JDK-only "
                                + "(String/Number/Boolean/List<String>) but was "
                                + v.getClass().getName() + " — Infinispan allow-list (GMLC C7)");
            }
        }
        return copy;
    }
}