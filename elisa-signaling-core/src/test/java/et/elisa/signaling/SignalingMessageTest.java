package et.elisa.signaling;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SignalingMessageTest {

    @Test
    void diameterMessageHasExpectedKeys() {
        var msg = new SignalingMessage(
                SignalingMessage.Protocol.DIAMETER,
                16777251L, 316, true, true, 0, 0, 10,
                "hss-a.epc.mnc01.mcc452.3gppnetwork.org",
                "epc.mnc01.mcc452.3gppnetwork.org",
                "mme-01.epc.mnc01.mcc452.3gppnetwork.org",
                "epc.mnc01.mcc452.3gppnetwork.org",
                "sess-123;12345",
                Map.of("IMSI", "452040212345678", "DEST_HOST", "hss-a.epc.mnc01.mcc452.3gppnetwork.org"),
                Map.of(),
                "mme-01",
                null);

        assertEquals(SignalingMessage.Protocol.DIAMETER, msg.protocol());
        assertEquals(16777251L, msg.appOrOp());
        assertEquals(316, msg.commandCode());
        assertTrue(msg.isRequest());
        assertEquals("452040212345678", msg.key("IMSI"));
        assertEquals("mme-01", msg.ingressPeerId());
    }

    @Test
    void ss7MessagePlaceholder() {
        var msg = new SignalingMessage(
                SignalingMessage.Protocol.SS7,
                4L, 0, true, false, 0, 0, 10,
                null, null, null, null, null,
                Map.of("GT", "2519112345678"),
                Map.of(),
                "stp-link-1",
                null);

        assertEquals(SignalingMessage.Protocol.SS7, msg.protocol());
        assertEquals(4L, msg.appOrOp());
        assertEquals("2519112345678", msg.key("GT"));
    }

    @Test
    void routingContextFromMessage() {
        var msg = new SignalingMessage(
                SignalingMessage.Protocol.DIAMETER,
                16777251L, 316, true, true, 0, 0, 10,
                "hss-a.example.com", "epc.example.com",
                "mme-01.example.com", "epc.example.com",
                "sess-1", Map.of("IMSI", "4520402123"),
                Map.of(), "mme-01", null);

        var ctx = RoutingContext.from(msg);
        assertEquals(SignalingMessage.Protocol.DIAMETER, ctx.protocol());
        assertEquals(16777251L, ctx.appOrOp());
        assertEquals("mme-01", ctx.ingressPeerId());
        assertEquals("4520402123", ctx.key("IMSI"));
    }

    @Test
    void routingContextKeyLookup() {
        var ctx = new RoutingContext(
                "peer-1", SignalingMessage.Protocol.DIAMETER,
                16777251L, 316, true, true, 0, 0, 10,
                "dest.example.com", "epc.example.com",
                "orig.example.com", "epc.example.com",
                Map.of("IMSI", "4520402123", "MSISDN", "2519112345678"));

        assertEquals("4520402123", ctx.key("IMSI"));
        assertEquals("2529112345678", ctx.key("MSISDN"));
        assertNull(ctx.key("NONEXISTENT"));
    }

    @Test
    void matcherProtocolIs() {
        var ctx = new RoutingContext(
                "peer-1", SignalingMessage.Protocol.DIAMETER,
                16777251L, 316, true, true, 0, 0, 10,
                null, null, null, null, Map.of());

        var m = new Matcher.ProtocolIs(SignalingMessage.Protocol.DIAMETER);
        assertTrue(m.evaluate(ctx));

        var m2 = new Matcher.ProtocolIs(SignalingMessage.Protocol.SS7);
        assertFalse(m2.evaluate(ctx));
    }

    @Test
    void routeDecisionForward() {
        var fwd = RouteDecision.Forward.plain("hss-pool");
        assertEquals("hss-pool", fwd.group());
        assertTrue(fwd.failoverEnabled());
        assertEquals(ThMode.OFF, fwd.th());
        assertTrue(fwd.ops().isEmpty());
    }

    @Test
    void routeDecisionReject() {
        var rej = new RouteDecision.Reject(3002, "no-route");
        assertEquals(3002, rej.resultCode());
        assertEquals("no-route", rej.reason());
    }

    @Test
    void matcherPathNamesCanonical() {
        assertEquals("IMSI", Matcher.PathNames.canonical("imsi"));
        assertEquals("IMSI", Matcher.PathNames.canonical("1"));
        assertEquals("MSISDN", Matcher.PathNames.canonical("701"));
        assertEquals("GT", Matcher.PathNames.canonical("gt"));
        assertEquals("OPC", Matcher.PathNames.canonical("opc"));
    }

    @Test
    void matcherHasAppEvaluates() {
        var ctx = new RoutingContext(
                "peer-1", SignalingMessage.Protocol.DIAMETER,
                16777251L, 316, true, true, 0, 0, 10,
                null, null, null, null, Map.of());

        assertTrue(new Matcher.HasApp(16777251L).evaluate(ctx));
        assertFalse(new Matcher.HasApp(16777238L).evaluate(ctx));
    }

    @Test
    void matcherPlmnMatchFromImsi() {
        var ctx = new RoutingContext(
                "peer-1", SignalingMessage.Protocol.DIAMETER,
                16777251L, 316, true, true, 0, 0, 10,
                null, null, null, null,
                Map.of("IMSI", "452040212345678"));

        var m = new Matcher.PlmnMatch("IMSI", Set.of("45204", "45201"), Set.of());
        assertTrue(m.evaluate(ctx));

        var m2 = new Matcher.PlmnMatch("IMSI", Set.of("64001"), Set.of());
        assertFalse(m2.evaluate(ctx));
    }

    @Test
    void screeningPolicyDefault() {
        var policy = new ScreeningPolicy(Map.of());
        assertFalse(policy.known("any-peer"));
        assertEquals(ScreeningPolicy.PeeringRules.ALLOW_ALL, policy.forPeer("any-peer"));
    }

    @Test
    void overloadPolicyPriorityClamp() {
        assertEquals(OverloadPolicy.DEFAULT_PRIORITY, OverloadPolicy.clamp(-1));
        assertEquals(OverloadPolicy.DEFAULT_PRIORITY, OverloadPolicy.clamp(16));
        assertEquals(5, OverloadPolicy.clamp(5));
    }

    @Test
    void topologyPolicyDisabled() {
        assertEquals(ThMode.OFF, TopologyPolicy.DISABLED.defaultMode());
        assertFalse(TopologyPolicy.DISABLED.enabled());
    }

    @Test
    void peerHandleHealthy() {
        var p1 = new PeerHandle("hss-a", 70, 100, null);
        assertTrue(p1.healthy());

        var p2 = new PeerHandle("hss-b", 30, 100, 0);
        assertFalse(p2.healthy());

        var p3 = new PeerHandle("hss-c", 30, 100, 50);
        assertTrue(p3.healthy());
    }

    @Test
    void lbStrategyValues() {
        assertEquals(4, LbStrategy.values().length);
    }
}
