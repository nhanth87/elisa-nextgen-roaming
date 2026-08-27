package et.elisa.signaling;

public interface RoutingEngine {

    RoutingContext contextFor(SignalingMessage msg);

    RouteDecision resolve(RoutingContext ctx);
}
