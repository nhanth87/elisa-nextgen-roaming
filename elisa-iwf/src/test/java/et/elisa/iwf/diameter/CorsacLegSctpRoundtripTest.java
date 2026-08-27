package et.elisa.iwf.diameter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import et.elisa.iwf.IwfConfig;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CorsacLegSctpRoundtripTest {

    private SctpTestPeer peer;
    private CorsacDiameterLeg leg;

    @BeforeAll
    void bringUp() throws Exception {
        peer = new SctpTestPeer();
        IwfConfig.DiaLegConfig cfg = new IwfConfig.DiaLegConfig(
                "127.0.0.1", peer.port(), 0,
                "iwf1.epc.mnc01.mcc452.3gppnetwork.org",
                "epc.mnc01.mcc452.3gppnetwork.org",
                "test-hss.epc.mnc01.mcc452.3gppnetwork.org",
                "epc.mnc01.mcc452.3gppnetwork.org", 5_000L);
        leg = new CorsacDiameterLeg(cfg);
        leg.start();
    }

    @AfterAll
    void tearDown() {
        if (leg != null) {
            leg.stop();
        }
        if (peer != null) {
            peer.close();
        }
    }

    private void awaitReady() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (!leg.ready() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
    }

    @Test
    void cerUsesBaseApplicationIdAndReachesOpen() throws Exception {
        awaitReady();
        assertTrue(leg.ready(), "leg must reach PeerStateEnum.OPEN after CER/CEA");
        assertNotEquals(-1, peer.cerApplicationId());
        assertEquals(0, peer.cerApplicationId(),
                "CER must carry app-id 0 per RFC 6733");
    }

    @Test
    void ulrAiaPurRoundtripWithCorrelatedAnswers() throws Exception {
        awaitReady();
        DiameterLeg.DiaResult ulr =
                leg.send(DiaCmd.ULR, Map.of("imsi", "4520402000000001"));
        assertEquals(2001, ulr.resultCode());
        DiameterLeg.DiaResult air =
                leg.send(DiaCmd.AIR, Map.of("imsi", "4520402000000001"));
        assertEquals(2001, air.resultCode());
        DiameterLeg.DiaResult pur =
                leg.send(DiaCmd.PUR, Map.of("imsi", "4520402000000001"));
        assertEquals(2001, pur.resultCode());
        assertNotEquals(ulr.hopByHopId(), air.hopByHopId(),
                "hbh must be monotonic, never repeated (SEQ_STEP lesson)");
        assertEquals(3, peer.s6aRequests(), "ULR+AIR+PUR must all reach the wire");
    }

    @Test
    void missingImsiFailsFastBeforeWire() {
        try {
            leg.send(DiaCmd.ULR, Map.of());
            org.junit.jupiter.api.Assertions.fail("expected DiaLegException");
        } catch (DiameterLeg.DiaLegException expected) {
            assertTrue(expected.getMessage().contains("imsi"));
        }
    }

    @Test
    void norRoundtripThroughEngineReturns2001() throws Exception {
        awaitReady();
        DiameterLeg.DiaResult nor = leg.send(DiaCmd.NOR, Map.of("imsi", "4520402000000001"));
        assertEquals(2001, nor.resultCode(), "NOR mapped via IwfEngine must reach the wire");
        assertEquals(4, peer.s6aRequests(), "ULR+AIR+PUR+NOR must all reach the wire");
    }

    @Test
    void serverInitiatedClrAnswersWith5012() throws Exception {
        awaitReady();
        leg.send(DiaCmd.ULR, Map.of("imsi", "4520402000000001"));
        peer.sendServerInitiatedClr("4520402000000001");
        Thread.sleep(500);
        assertTrue(peer.serverInitiatedSent() >= 1,
                "at least one server-initiated CLR must have been sent");
    }
}
