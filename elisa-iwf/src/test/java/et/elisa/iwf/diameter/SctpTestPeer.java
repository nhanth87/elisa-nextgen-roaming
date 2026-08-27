package et.elisa.iwf.diameter;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import com.sun.nio.sctp.MessageInfo;
import com.sun.nio.sctp.SctpChannel;
import com.sun.nio.sctp.SctpServerChannel;

/**
 * Minimal kernel-SCTP Diameter peer for hermetic leg tests: accepts one
 * association, answers CER with CEA 2001, echoes ULR/AIR/PUR as 2001,
 * replies DWA to DWR.
 * Wire header per RFC 6733 §3: version(1) | msg-length(3) | flags(1)
 * | command-code(3) | application-id(4) | hbh(4) | e2e(4).
 */
final class SctpTestPeer implements AutoCloseable {

    static final int CMD_CER_CEA = 257;
    static final int CMD_DWR_DWA = 280;
    private static final String HSS_HOST = "test-hss.epc.mnc01.mcc452.3gppnetwork.org";
    private static final String REALM = "epc.mnc01.mcc452.3gppnetwork.org";

    private final SctpServerChannel server;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final LongAdder requestsSeen = new LongAdder();
    private final java.util.concurrent.atomic.AtomicInteger s6aRequests =
            new java.util.concurrent.atomic.AtomicInteger();
    private final AtomicInteger cerHeaderAppId = new AtomicInteger(-1);
    private final AtomicInteger serverInitiatedSent = new AtomicInteger();
    private volatile SctpChannel activeChannel;
    private final Thread acceptThread;

    SctpTestPeer() throws Exception {
        server = SctpServerChannel.open();
        server.bind(new InetSocketAddress("127.0.0.1", 0), 1);
        acceptThread = Thread.ofPlatform().name("sctp-test-peer").daemon().start(this::acceptLoop);
    }

    int port() throws Exception {
        return ((InetSocketAddress) server.getAllLocalAddresses().iterator().next()).getPort();
    }

    long requestsSeen() {
        return requestsSeen.sum();
    }

    long s6aRequests() {
        return s6aRequests.get();
    }

    int serverInitiatedSent() {
        return serverInitiatedSent.get();
    }

    /** Header application-id of the first CER — must be 0 (RFC 6733 base app). */
    int cerApplicationId() {
        return cerHeaderAppId.get();
    }

    private void acceptLoop() {
        try {
            while (running.get()) {
                SctpChannel channel = server.accept();
                if (channel == null) {
                    continue;
                }
                Thread.ofVirtual().name("sctp-test-conn").start(() -> serve(channel));
            }
        } catch (Exception closed) {
            // server closed
        }
    }

    private void serve(SctpChannel channel) {
        activeChannel = channel;
        ByteBuffer buf = ByteBuffer.allocate(65536);
        try (channel) {
            while (running.get()) {
                buf.clear();
                MessageInfo info = channel.receive(buf, null, null);
                if (info == null) {
                    continue;
                }
                buf.flip();
                if (buf.remaining() < 20) {
                    System.out.println("[test-peer] short frame " + buf.remaining());
                    continue;
                }
                byte[] frame = new byte[buf.remaining()];
                buf.get(frame);
                requestsSeen.increment();
                boolean isRequest = (frame[4] & 0x80) != 0;
                int cmd = cmdOf(frame);
                long appId = u32(frame, 8);
                System.out.printf("[test-peer] rx cmd=%d flags=%02x app=%d req=%b bytes=%d%n",
                        cmd, frame[4], appId, isRequest, frame.length);
                if (!isRequest) {
                    continue;
                }
                if (cmd == DiaCmd.ULR.cmdCode() || cmd == DiaCmd.AIR.cmdCode()
                        || cmd == DiaCmd.PUR.cmdCode() || cmd == DiaCmd.NOR.cmdCode()) {
                    s6aRequests.incrementAndGet();
                }
                if (cmd == CMD_CER_CEA && cerHeaderAppId.get() < 0) {
                    cerHeaderAppId.set((int) appId);
                }
                byte[] reply = respond(frame, cmd, appId);
                if (reply != null) {
                    System.out.println("[test-peer] -> tx answer cmd=" + cmdOf(reply));
                    channel.send(ByteBuffer.wrap(reply),
                            MessageInfo.createOutgoing(
                                    channel.getRemoteAddresses().iterator().next(), 0));
                }
            }
        } catch (Exception e) {
            if (running.get()) {
                System.out.println("[test-peer] serve error: " + e);
            }
        }
    }

    void sendServerInitiatedClr(String imsi) {
        try {
            SctpChannel ch = activeChannel;
            if (ch == null || !ch.isOpen()) {
                return;
            }
            long hbh = 99999L;
            long e2e = 88888L;
            int cmdCode = DiaCmd.CLR.cmdCode();
            int appId = (int) DiaCmd.S6A_APP_ID;
            ByteArrayOutputStream avps = new ByteArrayOutputStream(96);
            avps.writeBytes(avpUtf8(264, false, HSS_HOST));
            avps.writeBytes(avpUtf8(296, false, REALM));
            avps.writeBytes(avpUtf8(1, true, imsi));
            byte[] body = avps.toByteArray();
            byte[] header = new byte[20];
            header[0] = (byte) 0x80;
            putU24(header, 1, 20 + body.length);
            header[4] = 0x00;
            header[5] = (byte) ((cmdCode >> 16) & 0xFF);
            header[6] = (byte) ((cmdCode >> 8) & 0xFF);
            header[7] = (byte) (cmdCode & 0xFF);
            putU32(header, 8, appId);
            putU32(header, 12, hbh);
            putU32(header, 16, e2e);
            ByteArrayOutputStream msg = new ByteArrayOutputStream(20 + body.length);
            msg.writeBytes(header);
            msg.writeBytes(body);
            ch.send(ByteBuffer.wrap(msg.toByteArray()),
                    MessageInfo.createOutgoing(
                            ch.getRemoteAddresses().iterator().next(), 0));
            serverInitiatedSent.incrementAndGet();
        } catch (Exception e) {
            System.err.println("[test-peer] send CLR failed: " + e.getMessage());
        }
    }

    private static int cmdOf(byte[] frame) {
        return ((frame[5] & 0xFF) << 16) | ((frame[6] & 0xFF) << 8) | (frame[7] & 0xFF);
    }

    private byte[] respond(byte[] req, int cmd, long appId) {
        long hbh = u32(req, 12);
        long e2e = u32(req, 16);
        ByteArrayOutputStream avps = new ByteArrayOutputStream(96);
        avps.writeBytes(avpU32(268, true, 2001));
        avps.writeBytes(avpUtf8(264, false, HSS_HOST));
        avps.writeBytes(avpUtf8(296, false, REALM));
        if (cmd == CMD_CER_CEA) {
            avps.writeBytes(avpU32(258, true, DiaCmd.S6A_APP_ID));
        }
        byte[] body = avps.toByteArray();
        byte[] header = new byte[20];
        header[0] = 1;
        putU24(header, 1, 20 + body.length);
        header[4] = 0x00;
        header[5] = (byte) ((cmd >> 16) & 0xFF);
        header[6] = (byte) ((cmd >> 8) & 0xFF);
        header[7] = (byte) (cmd & 0xFF);
        putU32(header, 8, appId);
        putU32(header, 12, hbh);
        putU32(header, 16, e2e);
        ByteArrayOutputStream msg = new ByteArrayOutputStream(20 + body.length);
        msg.writeBytes(header);
        msg.writeBytes(body);
        return msg.toByteArray();
    }

    private static long u32(byte[] b, int off) {
        return ((b[off] & 0xFFL) << 24) | ((b[off + 1] & 0xFFL) << 16)
                | ((b[off + 2] & 0xFFL) << 8) | (b[off + 3] & 0xFFL);
    }

    private static void putU24(byte[] b, int off, int v) {
        b[off] = (byte) ((v >> 16) & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
        b[off + 2] = (byte) (v & 0xFF);
    }

    private static void putU32(byte[] b, int off, long v) {
        b[off] = (byte) ((v >> 24) & 0xFF);
        b[off + 1] = (byte) ((v >> 16) & 0xFF);
        b[off + 2] = (byte) ((v >> 8) & 0xFF);
        b[off + 3] = (byte) (v & 0xFF);
    }

    /** AVP header per RFC 6733 §4.1: code(32) | flags(8) | length(24). */
    private static byte[] avpU32(int code, boolean mandatory, long value) {
        var out = new byte[12];
        out[0] = (byte) ((code >> 24) & 0xFF);
        out[1] = (byte) ((code >> 16) & 0xFF);
        out[2] = (byte) ((code >> 8) & 0xFF);
        out[3] = (byte) (code & 0xFF);
        out[4] = (byte) (mandatory ? 0x40 : 0x00);
        out[5] = 0;
        out[6] = 0;
        out[7] = 12;
        putU32(out, 8, value);
        return out;
    }

    private static byte[] avpUtf8(int code, boolean mandatory, String value) {
        byte[] raw = value.getBytes(StandardCharsets.UTF_8);
        int paddedLen = (raw.length + 3) / 4 * 4;
        int totalLen = 8 + raw.length;
        byte[] out = new byte[8 + paddedLen];
        out[0] = (byte) ((code >> 24) & 0xFF);
        out[1] = (byte) ((code >> 16) & 0xFF);
        out[2] = (byte) ((code >> 8) & 0xFF);
        out[3] = (byte) (code & 0xFF);
        out[4] = (byte) (mandatory ? 0x40 : 0x00);
        out[5] = (byte) ((totalLen >> 16) & 0xFF);
        out[6] = (byte) ((totalLen >> 8) & 0xFF);
        out[7] = (byte) (totalLen & 0xFF);
        System.arraycopy(raw, 0, out, 8, raw.length);
        return out;
    }

    @Override
    public void close() {
        running.set(false);
        try {
            server.close();
        } catch (Exception ignored) {
            // already closed
        }
        acceptThread.interrupt();
    }
}
