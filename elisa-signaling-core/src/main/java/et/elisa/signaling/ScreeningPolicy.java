package et.elisa.signaling;

import java.util.Map;
import java.util.Set;

public record ScreeningPolicy(Map<String, PeeringRules> peerings, boolean rejectUnknown) {

    public record PeeringRules(Set<String> appIds, Set<String> cmdCodes,
                               Set<String> realmSuffixes, Set<String> ipPrefixes,
                               boolean trustedNoProxy) {

        public static final PeeringRules ALLOW_ALL =
                new PeeringRules(Set.of(), Set.of(), Set.of(), Set.of(), false);

        public PeeringRules {
            appIds = Set.copyOf(appIds);
            cmdCodes = Set.copyOf(cmdCodes);
            realmSuffixes = Set.copyOf(realmSuffixes);
            ipPrefixes = Set.copyOf(ipPrefixes);
        }

        public boolean unrestricted() {
            return appIds.isEmpty() && cmdCodes.isEmpty() && realmSuffixes.isEmpty();
        }
    }

    public ScreeningPolicy {
        peerings = Map.copyOf(peerings);
    }

    public ScreeningPolicy(Map<String, PeeringRules> peerings) {
        this(peerings, false);
    }

    public boolean known(String peerId) {
        return peerings.containsKey(peerId);
    }

    public PeeringRules forPeer(String peerId) {
        return peerings.getOrDefault(peerId, PeeringRules.ALLOW_ALL);
    }
}
