package et.elisa.signaling;

import java.util.List;

public sealed interface RouteDecision {

    record Forward(String group, StickyBinding sticky, boolean failoverEnabled,
                   ThMode th, List<AvpOp> ops,
                   String preferredPeerId) implements RouteDecision {

        public Forward {
            ops = ops == null ? List.of() : List.copyOf(ops);
        }

        public static Forward plain(String group) {
            return new Forward(group, null, true, ThMode.OFF,
                    List.of(), null);
        }
    }

    record Redirect(String host, String realm, long cacheSeconds) implements RouteDecision {
    }

    record Reject(int resultCode, String reason) implements RouteDecision {
    }
}
