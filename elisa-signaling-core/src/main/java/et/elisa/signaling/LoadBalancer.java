package et.elisa.signaling;

import java.util.List;

public interface LoadBalancer {

    PeerHandle choose(List<PeerHandle> candidates, String preferredPeerId);
}
