package et.elisa.signaling;

import java.util.Map;
import java.util.Objects;

public record SignalingMessage(
        Protocol protocol,
        long appOrOp,
        int commandCode,
        boolean isRequest,
        boolean proxiable,
        int errorBit,
        int retransmitBit,
        int drmpPriority,
        String destHost,
        String destRealm,
        String origHost,
        String origRealm,
        String sessionId,
        Map<String, String> keys,
        Map<String, Object> avps,
        String ingressPeerId,
        byte[] rawBytes
) {

    public SignalingMessage {
        Objects.requireNonNull(protocol, "protocol");
        keys = keys == null ? Map.of() : Map.copyOf(keys);
        avps = avps == null ? Map.of() : Map.copyOf(avps);
    }

    public String key(String name) {
        return keys.get(name);
    }

    public static final int DRMP_DEFAULT = 10;

    public enum Protocol {
        DIAMETER,
        SS7,
        HTTP2
    }
}
