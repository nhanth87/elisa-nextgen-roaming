package et.elisa.signaling;

import java.util.Map;

public record RoutingContext(String ingressPeerId, SignalingMessage.Protocol protocol,
                             long appOrOp, int commandCode, boolean isRequest,
                             boolean proxiable, int errorBit, int retransmitBit,
                             int drmpPriority, String destHost, String destRealm,
                             String origHost, String origRealm,
                             Map<String, String> keys) {

    public static final int DRMP_DEFAULT = 10;

    public RoutingContext {
        keys = keys == null ? Map.of() : Map.copyOf(keys);
    }

    public String key(String name) {
        String value = keys.get(name);
        if (value != null && "MSISDN".equals(Matcher.PathNames.canonical(name))) {
            return Msisdn.normalize(value);
        }
        return value;
    }

    static final class Msisdn {
        private Msisdn() {
        }

        static String normalize(String raw) {
            // Ethiopia E.164 MSISDN (+251 9XXXXXXXXX): canonical SS7/Gr form uses the 29 prefix.
            if (raw != null && raw.startsWith("2519")
                    && raw.length() >= 4 + 4 && raw.substring(4).chars().allMatch(Character::isDigit)) {
                return "2529" + raw.substring(4);
            }
            return raw;
        }
    }

    public static RoutingContext from(SignalingMessage msg) {
        return new RoutingContext(
                msg.ingressPeerId(), msg.protocol(), msg.appOrOp(), msg.commandCode(),
                msg.isRequest(), msg.proxiable(), msg.errorBit(), msg.retransmitBit(),
                msg.drmpPriority(), msg.destHost(), msg.destRealm(),
                msg.origHost(), msg.origRealm(), msg.keys());
    }
}
