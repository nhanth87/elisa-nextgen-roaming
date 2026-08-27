package et.elisa.dra.lab.testapp.diameter;

import java.time.Instant;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.mobius.software.telco.protocols.diameter.AsyncCallback;
import com.mobius.software.telco.protocols.diameter.app.ServerAuthSessionStateless;
import com.mobius.software.telco.protocols.diameter.commands.DiameterRequest;
import com.mobius.software.telco.protocols.diameter.commands.slh.LCSRoutingInfoRequest;
import com.mobius.software.telco.protocols.diameter.commands.slh.LCSRoutingInfoAnswer;
import com.mobius.software.telco.protocols.diameter.exceptions.DiameterException;

import et.elisa.dra.lab.testapp.HssSimulator;

final class SLhHandler implements com.mobius.software.telco.protocols.diameter.app.slh.ServerListener {

    private static final long SLH_APP_ID = 16777291L;
    private static final Logger LOG = LogManager.getLogger(SLhHandler.class);

    private final HssSimulator hss;
    private final com.mobius.software.telco.protocols.diameter.app.slh.MessageFactory messages;

    SLhHandler(HssSimulator hss, com.mobius.software.telco.protocols.diameter.app.slh.MessageFactory messages) {
        this.hss = hss;
        this.messages = messages;
    }

    @Override
    public void onInitialRequest(LCSRoutingInfoRequest request,
            ServerAuthSessionStateless<LCSRoutingInfoAnswer> session, String linkID,
            AsyncCallback callback) {
        try {
            Answers.received(hss, "RIR", request, "user=" + Answers.usernameOf(request));
            LCSRoutingInfoAnswer answer = messages.createLCSRoutingInfoAnswer(request,
                    request.getHopByHopIdentifier(), request.getEndToEndIdentifier(),
                    Answers.SUCCESS);
            tagVendorAppId(answer, SLH_APP_ID);
            session.sendInitialAnswer(answer, callback);
        } catch (Exception e) {
            LOG.warn("SLh handler failure", e);
            try {
                LCSRoutingInfoAnswer fallback = messages.createLCSRoutingInfoAnswer(request,
                        request.getHopByHopIdentifier(), request.getEndToEndIdentifier(),
                        Answers.UNABLE_TO_DELIVER);
                tagVendorAppId(fallback, SLH_APP_ID);
                session.sendInitialAnswer(fallback, callback);
            } catch (Exception fatal) {
                LOG.error("SLh fail-safe answer failed", fatal);
                callback.onError(new DiameterException("handler failure", null,
                        Answers.UNABLE_TO_DELIVER, null));
            }
        }
    }

    private static void tagVendorAppId(
            com.mobius.software.telco.protocols.diameter.commands.commons.VendorSpecificAnswer answer,
            long authAppId) {
        try {
            answer.setVendorSpecificApplicationId(
                    new com.mobius.software.telco.protocols.diameter.impl.primitives.common.VendorSpecificApplicationIdImpl(
                            null, authAppId, null));
        } catch (Exception e) {
            LOG.warn("cannot tag Vendor-Specific-Application-Id: {}", e.toString());
        }
    }

    @Override
    public void onTimeout(DiameterRequest request,
            com.mobius.software.telco.protocols.diameter.DiameterSession session) {
        LOG.warn("SLh server timeout session={}", session == null ? "?" : session.getID());
    }

    @Override
    public void onIdleTimeout(com.mobius.software.telco.protocols.diameter.DiameterSession session) {
    }
}
