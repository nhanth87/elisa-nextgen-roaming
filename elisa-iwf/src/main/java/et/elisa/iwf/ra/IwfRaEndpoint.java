package et.elisa.iwf.ra;

import com.microjainslee.api.ActivityHandle;
import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.RaEndpointPort;
import com.microjainslee.api.SleeEvent;

import com.mobius.software.telco.protocols.diameter.commands.DiameterAnswer;

/**
 * micro-jainslee RA endpoint for the Diameter leg: fabric ingress fires
 * container events (activity key = session id, fallback hbh), outbound
 * commands go back onto the DRA link.
 */
public final class IwfRaEndpoint implements RaEndpointPort, RaCommandPort {

    public static final String RA_NAME = "iwf-diameter-ra";

    /** Outbound sink — the live corsac link surface. */
    public interface OutboundSink {
        void sendOnLink(String linkId, Object message);
    }

    private final OutboundSink sink;
    private volatile IngressTrigger trigger;
    private volatile boolean started;

    @FunctionalInterface
    public interface IngressTrigger {
        void onIngress(SleeEvent event);
    }

    public IwfRaEndpoint(OutboundSink sink) {
        this.sink = sink;
    }

    @Override
    public String getRaName() {
        return RA_NAME;
    }

    @Override
    public void activate(RaBootstrapPort bootstrap) {
        this.trigger = event -> fire(bootstrap, event);
        started = true;
    }

    private void fire(RaBootstrapPort bootstrap, SleeEvent event) {
        String activity = "iwf-misc/" + System.nanoTime();
        try {
            switch (event) {
                case IwfRequestEvent req -> activity = activityKey(
                        req.msg().getSessionId(), req.msg().getHopByHopIdentifier());
                case IwfAnswerEvent ans -> activity = activityKey(
                        ans.msg().getSessionId(), ans.hopByHopId());
                default -> {
                }
            }
        } catch (com.mobius.software.telco.protocols.diameter.exceptions.DiameterException e) {
            // sessionless frames key by hbh
        }
        ActivityHandle handle = bootstrap.createActivityHandle(activity);
        bootstrap.fireEvent(event, handle, null);
    }

    private static String activityKey(Object sessionId, Long hopByHopId) {
        /**
         * Activity key strategy: prefer Diameter Session-ID, fallback to hbh.
         * M-IWF-3: the {@link et.elisa.iwf.mapping.DialogBindingRegistry}
         * provides IMSI→dialogId binding so the MAP leg can route
         * server-initiated CLR/IDR/DSR to the correct TCAP dialog.
         */
        if (sessionId instanceof String s && !s.isBlank()) {
            return "iwf-sess/" + s;
        }
        return "iwf-hbh/" + Long.toHexString(hopByHopId == null ? 0L : hopByHopId);
    }

    /** Production entry: feed an RA-level ingress event into the container. */
    public void onRaIngress(SleeEvent event) {
        IngressTrigger t = trigger;
        if (t == null) {
            throw new IllegalStateException("iwf ra endpoint not activated");
        }
        t.onIngress(event);
    }

    @Override
    public void sendCommand(OutboundCommand command) {
        if (command instanceof IwfSendCommand send) {
            sink.sendOnLink(send.linkId(), send.msg());
            return;
        }
        throw new IllegalArgumentException("unsupported outbound command: "
                + (command == null ? "null" : command.getClass().getName()));
    }

    @Override
    public void deactivate() {
        started = false;
        trigger = null;
    }

    public boolean isStarted() {
        return started;
    }
}
