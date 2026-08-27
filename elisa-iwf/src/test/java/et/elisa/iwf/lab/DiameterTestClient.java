package et.elisa.iwf.lab;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import com.mobius.software.common.dal.timers.WorkerPool;
import com.mobius.software.telco.protocols.diameter.ApplicationIDs;
import com.mobius.software.telco.protocols.diameter.AsyncCallback;
import com.mobius.software.telco.protocols.diameter.DiameterLink;
import com.mobius.software.telco.protocols.diameter.DiameterStack;
import com.mobius.software.telco.protocols.diameter.PeerStateEnum;
import com.mobius.software.telco.protocols.diameter.commands.DiameterMessage;
import com.mobius.software.telco.protocols.diameter.commands.s6a.S6aAnswer;
import com.mobius.software.telco.protocols.diameter.exceptions.DiameterException;
import com.mobius.software.telco.protocols.diameter.impl.DiameterStackImpl;
import com.mobius.software.telco.protocols.diameter.impl.primitives.s6a.RequestedEUTRANAuthenticationInfoImpl;
import com.mobius.software.telco.protocols.diameter.impl.primitives.s6a.ULRFlagsImpl;
import com.mobius.software.telco.protocols.diameter.primitives.common.AuthSessionStateEnum;
import com.mobius.software.telco.protocols.diameter.parser.DiameterParser;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * CORSAC-based Diameter test client for IWF lab integration tests.
 * Supports S6a, Sh, Rx, Gx, Cx, S13, SLh, SLg, S6c, SWx.
 * Uses sendEncodedMessage to bypass corsac's canSendMessage() gate.
 */
public final class DiameterTestClient implements AutoCloseable {

    private static final String LINK_ID = "test-client";
    private static final String ORIGIN_HOST = "test-client.epc.mnc01.mcc452.3gppnetwork.org";
    private static final String ORIGIN_REALM = "epc.mnc01.mcc452.3gppnetwork.org";
    private static final String DRA_HOSTNAME = "dra1.epc.mnc01.mcc452.3gppnetwork.org";
    private static final String PRODUCT_NAME = "iwf-test-client";
    private static final int WORKER_THREADS = 2;

    private final String draHost;
    private final int draPort;
    private final int srcPort;

    private volatile DiameterStack stack;
    private volatile WorkerPool workerPool;
    private final AtomicInteger msgSeq = new AtomicInteger();
    private final LongAdder requestsSent = new LongAdder();
    private final LongAdder answersReceived = new LongAdder();

    public DiameterTestClient(String draHost, int draPort, int srcPort) {
        this.draHost = draHost;
        this.draPort = draPort;
        this.srcPort = srcPort;
    }

    public void start() throws Exception {
        workerPool = new WorkerPool("test-client-dia");
        workerPool.start(WORKER_THREADS);
        stack = new DiameterStackImpl(
                getClass().getClassLoader(),
                new org.restcomm.cluster.UUIDGenerator(),
                workerPool,
                WORKER_THREADS,
                ORIGIN_HOST,
                PRODUCT_NAME,
                0L, 10L, 120_000L, 60_000L, 2_000L, 0L, 0L);
        var nm = stack.getNetworkManager();
        nm.addNetworkListener(LINK_ID + "-ingress", this::onIngress);
        java.net.InetAddress remote = java.net.InetAddress.getByName(draHost);
        java.net.InetAddress local = remote.isLoopbackAddress()
                ? remote : java.net.InetAddress.getByName("0.0.0.0");
        nm.addLink(LINK_ID, remote, draPort, local, srcPort,
                Boolean.FALSE, Boolean.TRUE,
                ORIGIN_HOST, ORIGIN_REALM,
                DRA_HOSTNAME, ORIGIN_REALM,
                Boolean.FALSE, Boolean.FALSE);

        List<Long> allApps = List.of(
                (long) ApplicationIDs.S6A, (long) ApplicationIDs.SH,
                (long) ApplicationIDs.RX, (long) ApplicationIDs.GX,
                (long) ApplicationIDs.CX_DX, (long) ApplicationIDs.S13,
                (long) ApplicationIDs.SLH, (long) ApplicationIDs.SLG,
                (long) ApplicationIDs.S6C, (long) ApplicationIDs.SWX);

        nm.registerApplication(LINK_ID,
                List.of(), List.of((long) ApplicationIDs.S6A), List.of(),
                Class.forName("com.mobius.software.telco.protocols.diameter.impl.commands.s6a.UpdateLocationRequestImpl").getPackage(),
                Class.forName("com.mobius.software.telco.protocols.diameter.impl.commands.s6a.UpdateLocationAnswerImpl").getPackage());
        nm.registerApplication(LINK_ID,
                List.of(), List.of((long) ApplicationIDs.SH), List.of(),
                Class.forName("com.mobius.software.telco.protocols.diameter.impl.commands.sh.UserDataRequestImpl").getPackage(),
                Class.forName("com.mobius.software.telco.protocols.diameter.impl.commands.sh.UserDataAnswerImpl").getPackage());
        nm.registerApplication(LINK_ID,
                List.of(), List.of((long) ApplicationIDs.RX), List.of(),
                Class.forName("com.mobius.software.telco.protocols.diameter.impl.commands.rx.AARequestImpl").getPackage(),
                Class.forName("com.mobius.software.telco.protocols.diameter.impl.commands.rx.AAAnswerImpl").getPackage());
        nm.registerApplication(LINK_ID,
                List.of(), List.of((long) ApplicationIDs.GX), List.of(),
                Class.forName("com.mobius.software.telco.protocols.diameter.impl.commands.gx.CreditControlRequestImpl").getPackage(),
                Class.forName("com.mobius.software.telco.protocols.diameter.impl.commands.gx.CreditControlAnswerImpl").getPackage());
        nm.registerApplication(LINK_ID,
                List.of(), List.of((long) ApplicationIDs.CX_DX), List.of(),
                Class.forName("com.mobius.software.telco.protocols.diameter.impl.commands.cxdx.UserAuthorizationRequestImpl").getPackage(),
                Class.forName("com.mobius.software.telco.protocols.diameter.impl.commands.cxdx.UserAuthorizationAnswerImpl").getPackage());
        nm.registerApplication(LINK_ID,
                List.of(), List.of((long) ApplicationIDs.S13), List.of(),
                Class.forName("com.mobius.software.telco.protocols.diameter.impl.commands.s13.MEIdentityCheckRequestImpl").getPackage(),
                Class.forName("com.mobius.software.telco.protocols.diameter.impl.commands.s13.MEIdentityCheckAnswerImpl").getPackage());
        nm.registerApplication(LINK_ID,
                List.of(), List.of((long) ApplicationIDs.SLH), List.of(),
                Class.forName("com.mobius.software.telco.protocols.diameter.impl.commands.slh.LCSRoutingInfoRequestImpl").getPackage(),
                Class.forName("com.mobius.software.telco.protocols.diameter.impl.commands.slh.LCSRoutingInfoAnswerImpl").getPackage());
        nm.registerApplication(LINK_ID,
                List.of(), List.of((long) ApplicationIDs.SLG), List.of(),
                Class.forName("com.mobius.software.telco.protocols.diameter.impl.commands.slg.ProvideLocationRequestImpl").getPackage(),
                Class.forName("com.mobius.software.telco.protocols.diameter.impl.commands.slg.ProvideLocationAnswerImpl").getPackage());
        nm.registerApplication(LINK_ID,
                List.of(), List.of((long) ApplicationIDs.S6C), List.of(),
                Class.forName("com.mobius.software.telco.protocols.diameter.impl.commands.s6c.SendRoutingInfoForSMRequestImpl").getPackage(),
                Class.forName("com.mobius.software.telco.protocols.diameter.impl.commands.s6c.SendRoutingInfoForSMAnswerImpl").getPackage());
        nm.registerApplication(LINK_ID,
                List.of(), List.of((long) ApplicationIDs.SWX), List.of(),
                Class.forName("com.mobius.software.telco.protocols.diameter.impl.commands.swx.MultimediaAuthRequestImpl").getPackage(),
                Class.forName("com.mobius.software.telco.protocols.diameter.impl.commands.swx.MultimediaAuthAnswerImpl").getPackage());
        nm.startLink(LINK_ID);
    }

    public boolean waitForCer(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            DiameterLink l = link();
            if (l != null && l.getPeerState() == PeerStateEnum.OPEN) {
                return true;
            }
            Thread.sleep(200);
        }
        return false;
    }

    // ── S6a ──

    public com.mobius.software.telco.protocols.diameter.commands.s6a.UpdateLocationRequest
            sendUlr(String imsi, String visitedPlmn) throws Exception {
        var ulr = new com.mobius.software.telco.protocols.diameter.impl.commands.s6a.UpdateLocationRequestImpl(
                ORIGIN_HOST, ORIGIN_REALM, DRA_HOSTNAME, ORIGIN_REALM,
                Boolean.FALSE, nextSessionId(),
                AuthSessionStateEnum.NO_STATE_MAINTAINED,
                com.mobius.software.telco.protocols.diameter.primitives.gx.RATTypeEnum.EUTRAN,
                new ULRFlagsImpl(),
                visitedPlmn != null ? Unpooled.copiedBuffer(visitedPlmn, StandardCharsets.US_ASCII) : null);
        ulr.setUsername(imsi);
        send(ulr);
        return ulr;
    }

    public com.mobius.software.telco.protocols.diameter.commands.s6a.AuthenticationInformationRequest
            sendAir(String imsi, int requestedVectors) throws Exception {
        byte[] plmn = new byte[]{0x05, 0x20, 0x01};
        var air = new com.mobius.software.telco.protocols.diameter.impl.commands.s6a.AuthenticationInformationRequestImpl(
                ORIGIN_HOST, ORIGIN_REALM, DRA_HOSTNAME, ORIGIN_REALM,
                Boolean.FALSE, nextSessionId(),
                AuthSessionStateEnum.NO_STATE_MAINTAINED,
                Unpooled.wrappedBuffer(plmn));
        air.setUsername(imsi);
        var req = new RequestedEUTRANAuthenticationInfoImpl();
        req.setNumberOfRequestedVectors((long) requestedVectors);
        air.setRequestedEUTRANAuthenticationInfo(req);
        send(air);
        return air;
    }

    public com.mobius.software.telco.protocols.diameter.commands.s6a.PurgeUERequest
            sendPur(String imsi) throws Exception {
        var pur = new com.mobius.software.telco.protocols.diameter.impl.commands.s6a.PurgeUERequestImpl(
                ORIGIN_HOST, ORIGIN_REALM, DRA_HOSTNAME, ORIGIN_REALM,
                Boolean.FALSE, nextSessionId(),
                AuthSessionStateEnum.NO_STATE_MAINTAINED);
        pur.setUsername(imsi);
        send(pur);
        return pur;
    }

    // ── Sh (UDR) ──

    public DiameterMessage sendShUdr(String imsi) throws Exception {
        var userIdentity = new com.mobius.software.telco.protocols.diameter.impl.primitives.sh.UserIdentityImpl();
        userIdentity.setPublicIdentity("sip:" + imsi + "@ims.mnc001.mcc452.3gppnetwork.org");
        var udr = new com.mobius.software.telco.protocols.diameter.impl.commands.sh.UserDataRequestImpl(
                ORIGIN_HOST, ORIGIN_REALM, DRA_HOSTNAME, ORIGIN_REALM,
                Boolean.FALSE, nextSessionId(),
                AuthSessionStateEnum.NO_STATE_MAINTAINED,
                userIdentity,
                List.of(com.mobius.software.telco.protocols.diameter.primitives.sh.DataReferenceEnum.REPOSITORY_DATA));
        send(udr);
        return udr;
    }

    // ── Rx (AAR) ──

    public DiameterMessage sendRxAar() throws Exception {
        var aar = new com.mobius.software.telco.protocols.diameter.impl.commands.rx.AARequestImpl(
                ORIGIN_HOST, ORIGIN_REALM, DRA_HOSTNAME, ORIGIN_REALM,
                Boolean.FALSE, nextSessionId(), 0L);
        send(aar);
        return aar;
    }

    // ── Gx (CCR) ──

    public DiameterMessage sendGxCcr() throws Exception {
        var ccr = new com.mobius.software.telco.protocols.diameter.impl.commands.gx.CreditControlRequestImpl(
                ORIGIN_HOST, ORIGIN_REALM, DRA_HOSTNAME, ORIGIN_REALM,
                Boolean.FALSE, nextSessionId(),
                com.mobius.software.telco.protocols.diameter.primitives.creditcontrol.CcRequestTypeEnum.EVENT_REQUEST,
                msgSeq.incrementAndGet() & 0xFFFFFFFFL);
        send(ccr);
        return ccr;
    }

    // ── Cx/Dx (UAR) ──

    public DiameterMessage sendCxUar(String sipUserId) throws Exception {
        var uar = new com.mobius.software.telco.protocols.diameter.impl.commands.cxdx.UserAuthorizationRequestImpl(
                ORIGIN_HOST, ORIGIN_REALM, DRA_HOSTNAME, ORIGIN_REALM,
                Boolean.FALSE, nextSessionId(),
                AuthSessionStateEnum.NO_STATE_MAINTAINED);
        uar.setUsername(sipUserId);
        send(uar);
        return uar;
    }

    // ── S13 (ECR) ──

    public DiameterMessage sendS13Ecr() throws Exception {
        var ecr = new com.mobius.software.telco.protocols.diameter.impl.commands.s13.MEIdentityCheckRequestImpl(
                ORIGIN_HOST, ORIGIN_REALM, DRA_HOSTNAME, ORIGIN_REALM,
                Boolean.FALSE, nextSessionId(),
                AuthSessionStateEnum.NO_STATE_MAINTAINED);
        send(ecr);
        return ecr;
    }

    // ── SLh (RIR) ──

    public DiameterMessage sendSlhRir(String imsi) throws Exception {
        var rir = new com.mobius.software.telco.protocols.diameter.impl.commands.slh.LCSRoutingInfoRequestImpl(
                ORIGIN_HOST, ORIGIN_REALM, DRA_HOSTNAME, ORIGIN_REALM,
                Boolean.FALSE, nextSessionId(),
                AuthSessionStateEnum.NO_STATE_MAINTAINED);
        rir.setMSISDN(imsi);
        send(rir);
        return rir;
    }

    // ── SLg (PLR) ──

    public DiameterMessage sendSlgPlr(String imsi) throws Exception {
        var clientName = new com.mobius.software.telco.protocols.diameter.impl.primitives.slg.LCSEPSClientNameImpl();
        clientName.setLCSNameString("LCS-Client");
        var plr = new com.mobius.software.telco.protocols.diameter.impl.commands.slg.ProvideLocationRequestImpl(
                ORIGIN_HOST, ORIGIN_REALM, DRA_HOSTNAME, ORIGIN_REALM,
                Boolean.FALSE, nextSessionId(),
                AuthSessionStateEnum.NO_STATE_MAINTAINED,
                com.mobius.software.telco.protocols.diameter.primitives.slg.SLgLocationTypeEnum.CURRENT_LOCATION,
                clientName,
                com.mobius.software.telco.protocols.diameter.primitives.accounting.LCSClientTypeEnum.VALUE_ADDED_SERVICES);
        send(plr);
        return plr;
    }

    // ── S6c (SRR) ──

    public DiameterMessage sendS6cSrr(String imsi) throws Exception {
        var srr = new com.mobius.software.telco.protocols.diameter.impl.commands.s6c.SendRoutingInfoForSMRequestImpl(
                ORIGIN_HOST, ORIGIN_REALM, DRA_HOSTNAME, ORIGIN_REALM,
                Boolean.FALSE, nextSessionId(),
                AuthSessionStateEnum.NO_STATE_MAINTAINED);
        srr.setMSISDN(imsi);
        send(srr);
        return srr;
    }

    // ── SWx (MAR) ──

    public DiameterMessage sendSwxMar(String imsi) throws Exception {
        var sipAuth = new com.mobius.software.telco.protocols.diameter.impl.primitives.cxdx.SIPAuthDataItemImpl();
        sipAuth.setSIPAuthenticationScheme("EAP-AKA");
        var mar = new com.mobius.software.telco.protocols.diameter.impl.commands.swx.MultimediaAuthRequestImpl(
                ORIGIN_HOST, ORIGIN_REALM, DRA_HOSTNAME, ORIGIN_REALM,
                Boolean.FALSE, nextSessionId(),
                AuthSessionStateEnum.NO_STATE_MAINTAINED,
                imsi, 0L, sipAuth);
        send(mar);
        return mar;
    }

    // ── Internal ──

    private String nextSessionId() {
        return ORIGIN_HOST + ";" + msgSeq.incrementAndGet();
    }

    private void send(DiameterMessage msg) throws Exception {
        requestsSent.increment();
        DiameterParser parser = ((DiameterStackImpl) stack).getGlobalParser();
        ByteBuf buf = parser.encode(msg);
        link().sendEncodedMessage(buf, noop());
    }

    private void onIngress(DiameterMessage msg, String linkID, AsyncCallback callback) {
        answersReceived.increment();
        System.out.println("[test-client] received: " + msg.getClass().getSimpleName());
    }

    private DiameterLink requireLink() {
        DiameterLink l = link();
        if (l == null) throw new IllegalStateException("link not ready");
        return l;
    }

    private DiameterLink link() {
        DiameterStack s = stack;
        return s == null ? null : s.getNetworkManager().getLink(LINK_ID);
    }

    private static AsyncCallback noop() {
        return new AsyncCallback() {
            @Override public void onSuccess() {}
            @Override public void onError(DiameterException e) {
                System.err.println("[test-client] SEND ERROR: " + e.getMessage());
            }
        };
    }

    public long requestsSent() { return requestsSent.sum(); }
    public long answersReceived() { return answersReceived.sum(); }

    @Override
    public void close() {
        DiameterStack s = stack;
        stack = null;
        if (s != null) { try { s.stop(); } catch (Exception ignored) {} }
        WorkerPool wp = workerPool;
        workerPool = null;
        if (wp != null) { try { wp.stop(); } catch (Exception ignored) {} }
    }
}
