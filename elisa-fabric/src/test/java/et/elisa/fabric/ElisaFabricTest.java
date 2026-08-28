package et.elisa.fabric;

import com.microjainslee.cluster.ClusterManager;
import com.microjainslee.core.MicroSleeConfiguration;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElisaFabricTest {

    @Test
    void draOwnsRoutePolicyThMapAndPublishesPeers() {
        ClusterManager cm = newCluster("t-dra");
        try {
            ElisaFabric fabric = new ElisaFabric(cm, FabricRole.DRA);

            fabric.put(ElisaFabricCache.PEER_TOPOLOGY,
                    "epc.mnc01.mcc452.3gppnetwork.org",
                    Map.of(
                            "host", "mme-01.epc.mnc01.mcc452.3gppnetwork.org",
                            "port", 3868L,
                            "apps", List.of("16777251", "16777236"),
                            "transport", "SCTP"));

            fabric.put(ElisaFabricCache.ROUTE_POLICY,
                    "epc.mnc01.mcc452.3gppnetwork.org/16777251",
                    Map.of("group", "mvno-hss-pool", "priority", 10, "thMode", "ON"));

            fabric.put(ElisaFabricCache.TH_MAP,
                    "hss-internal.epc.mnc01.mcc452.3gppnetwork.org",
                    Map.of("public", "hss-a.dra-edge.example.com", "thMode", "ON"));

            assertEquals(1, fabric.size(ElisaFabricCache.PEER_TOPOLOGY));
            assertEquals("mme-01.epc.mnc01.mcc452.3gppnetwork.org",
                    fabric.get(ElisaFabricCache.PEER_TOPOLOGY,
                            "epc.mnc01.mcc452.3gppnetwork.org").get("host"));
            assertEquals(10, fabric.get(ElisaFabricCache.ROUTE_POLICY,
                    "epc.mnc01.mcc452.3gppnetwork.org/16777251").get("priority"));
            assertEquals("mvno-hss-pool",
                    fabric.get(ElisaFabricCache.ROUTE_POLICY,
                            "epc.mnc01.mcc452.3gppnetwork.org/16777251").get("group"));
            assertTrue(fabric.status().get("cluster.mode").equals("LOCAL"));
        } finally {
            cm.stop();
        }
    }

    @Test
    void nonOwnerWriteIsRejected() {
        ClusterManager cm = newCluster("t-iwf");
        try {
            ElisaFabric fabric = new ElisaFabric(cm, FabricRole.IWF);
            assertThrows(IllegalStateException.class, () ->
                    fabric.put(ElisaFabricCache.ROUTE_POLICY,
                            "epc.mnc01.mcc452.3gppnetwork.org/16777251",
                            Map.of("group", "x", "priority", 1, "thMode", "OFF")));
            // but IWF owns its anchoring caches
            fabric.put(ElisaFabricCache.IMSI_CONTEXT, "452040212345678",
                    Map.of("ingressPeer", "iwf-1", "dialOwnerNodeId", "iwf-1",
                            "mapOp", "updateGprsLocation", "sessionId", "s1"));
            assertEquals(1, fabric.size(ElisaFabricCache.IMSI_CONTEXT));
        } finally {
            cm.stop();
        }
    }

    @Test
    void validatorRejectsBadValuesFailFast() {
        ClusterManager cm = newCluster("t-dra");
        try {
            ElisaFabric fabric = new ElisaFabric(cm, FabricRole.DRA);

            // bad realm
            assertThrows(IllegalArgumentException.class, () ->
                    fabric.put(ElisaFabricCache.PEER_TOPOLOGY, "not a realm", Map.of("host", "x")));
            // missing required peer-topology field
            assertThrows(IllegalArgumentException.class, () ->
                    fabric.put(ElisaFabricCache.PEER_TOPOLOGY,
                            "epc.mnc01.mcc452.3gppnetwork.org", Map.of("host", "x")));
            // bad route key (appId non-numeric)
            assertThrows(IllegalArgumentException.class, () ->
                    fabric.put(ElisaFabricCache.ROUTE_POLICY,
                            "epc.mnc01.mcc452.3gppnetwork.org/notanapp",
                            Map.of("group", "x", "priority", 1, "thMode", "OFF")));
            // peer-state wrong enum
            fabric.put(ElisaFabricCache.PEER_STATE, "iwf-leg",
                    Map.of("state", "READY", "sinceMs", 1L));
            assertThrows(IllegalArgumentException.class, () ->
                    fabric.put(ElisaFabricCache.PEER_STATE, "iwf-leg-bad",
                            Map.of("state", "MAYBE")));
            // imsi must be 15 digits
            ClusterManager iwfCm = newCluster("t-iwf");
            ElisaFabric iwf = new ElisaFabric(iwfCm, FabricRole.IWF);
            assertThrows(IllegalArgumentException.class, () ->
                    iwf.put(ElisaFabricCache.IMSI_CONTEXT, "123",
                            Map.of("ingressPeer", "a", "dialOwnerNodeId", "b")));
            // non-JDK-only value rejected
            Map<String, Object> bad = new HashMap<>();
            bad.put("payload", new Object());
            assertThrows(IllegalArgumentException.class, () ->
                    iwf.put(ElisaFabricCache.IMSI_CONTEXT, "452040212345678",
                            Map.of("ingressPeer", "a", "dialOwnerNodeId", "b", "extra", bad)));
            iwfCm.stop();
        } finally {
            cm.stop();
        }
    }

    @Test
    void peerStateSharedAmongOwners() {
        ClusterManager cm = newCluster("t-stp");
        try {
            ElisaFabric stp = new ElisaFabric(cm, FabricRole.STP);
            stp.put(ElisaFabricCache.PEER_STATE, "iwf-leg",
                    Map.of("state", "READY", "sinceMs", 1_700_000_000_000L));

            // gtt-public owner = STP
            stp.put(ElisaFabricCache.GTT_PUBLIC, "251911",
                    Map.of("pointCode", 250, "subsystemNumber", 11));
            stp.put(ElisaFabricCache.LEASES, "10.0.0.1:2905",
                    Map.of("nodeId", "stp-node-1", "leaseUntilMs", 1_700_000_000_000L));

            assertEquals("READY", stp.get(ElisaFabricCache.PEER_STATE, "iwf-leg").get("state"));
            assertEquals(11, stp.get(ElisaFabricCache.GTT_PUBLIC, "251911").get("subsystemNumber"));
            assertEquals(Set.of("251911"), stp.keys(ElisaFabricCache.GTT_PUBLIC));
        } finally {
            cm.stop();
        }
    }

    @Test
    void configIsDualOwnedAndValidated() {
        ClusterManager cm = newCluster("t-dra");
        try {
            ElisaFabric fabric = new ElisaFabric(cm, FabricRole.DRA);
            fabric.put(ElisaFabricCache.CONFIG, "iwf.diameter.origin-host",
                    Map.of("value", "iwf1.dra-edge.example.com"));
            assertEquals("iwf1.dra-edge.example.com",
                    fabric.get(ElisaFabricCache.CONFIG, "iwf.diameter.origin-host").get("value"));

            ElisaFabric stp = new ElisaFabric(newCluster("t-stp"), FabricRole.STP);
            try {
                stp.put(ElisaFabricCache.CONFIG, "stp.ss7.gt",
                        Map.of("value", "3110000000"));
                assertTrue(stp.get(ElisaFabricCache.CONFIG, "stp.ss7.gt") != null);
            } finally {
                stp.clusterManager().stop();
            }
            assertThrows(IllegalArgumentException.class, () ->
                    fabric.put(ElisaFabricCache.CONFIG, "x", Map.of("value", "  ")));
        } finally {
            cm.stop();
        }
    }

    @Test
    void dialogBindRequiresValidImsi() {
        ClusterManager cm = newCluster("t-iwf");
        try {
            ElisaFabric fabric = new ElisaFabric(cm, FabricRole.IWF);
            fabric.put(ElisaFabricCache.DIALOG_BIND, "sess-123;456",
                    Map.of("imsi", "452040212345678"));
            assertEquals("452040212345678",
                    fabric.get(ElisaFabricCache.DIALOG_BIND, "sess-123;456").get("imsi"));
            assertThrows(IllegalArgumentException.class, () ->
                    fabric.put(ElisaFabricCache.DIALOG_BIND, "sess-x",
                            Map.of("imsi", "123")));
            // numeric dialog keys (hbh/session-id) are legal — spacing/illegal chars are not
            fabric.put(ElisaFabricCache.DIALOG_BIND, "123",
                    Map.of("imsi", "452040212345678"));
            assertThrows(IllegalArgumentException.class, () ->
                    fabric.put(ElisaFabricCache.DIALOG_BIND, "bad key",
                            Map.of("imsi", "452040212345678")));
        } finally {
            cm.stop();
        }
    }

    @Test
    void fabricValuesMustBeJdkOnly() {
        ClusterManager cm = newCluster("t-iwf");
        try {
            ElisaFabric fabric = new ElisaFabric(cm, FabricRole.IWF);
            Map<String, Object> nested = new HashMap<>();
            nested.put("k", "v");
            assertThrows(IllegalArgumentException.class, () ->
                    fabric.put(ElisaFabricCache.IMSI_CONTEXT, "452040212345678",
                            Map.of("ingressPeer", "a", "dialOwnerNodeId", "b", "nested", nested)));
        } finally {
            cm.stop();
        }
    }

    private static ClusterManager newCluster(String node) {
        ClusterManager manager = new ClusterManager(
                MicroSleeConfiguration.builder().clusterEnabled(false).nodeId(node).build(),
                node);
        manager.start();
        return manager;
    }
}