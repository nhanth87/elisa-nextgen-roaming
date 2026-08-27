package et.elisa.signaling;

public interface AdapterSPI {

    String protocolName();

    SignalingMessage decode(Object rawEvent, String ingressPeerId);

    Object encode(SignalingMessage msg, RouteDecision decision);

    boolean canHandle(String appIdOrOp);

    SignalingMessage.Protocol protocol();
}
