package et.elisa.dra.lab.testapp.diameter;

import java.time.Instant;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.mobius.software.telco.protocols.diameter.AsyncCallback;
import com.mobius.software.telco.protocols.diameter.app.ServerAuthSessionStateless;
import com.mobius.software.telco.protocols.diameter.commands.DiameterRequest;
import com.mobius.software.telco.protocols.diameter.commands.s6c.S6cRequest;
import com.mobius.software.telco.protocols.diameter.commands.s6c.S6cAnswer;
import com.mobius.software.telco.protocols.diameter.commands.s6c.SendRoutingInfoForSMRequest;
import com.mobius.software.telco.protocols.diameter.commands.s6c.SendRoutingInfoForSMAnswer;
import com.mobius.software.telco.protocols.diameter.commands.s6c.AlertServiceCentreRequest;
import com.mobius.software.telco.protocols.diameter.commands.s6c.AlertServiceCentreAnswer;
import com.mobius.software.telco.protocols.diameter.exceptions.DiameterException;

import et.elisa.dra.lab.testapp.HssSimulator;

final class S6cHandler implements com.mobius.software.telco.protocols.diameter.app.s6c.ServerListener {

    private static final long S6C_APP_ID = 16777312L;
    private static final Logger LOG = LogManager.getLogger(S6cHandler.class);

    private final HssSimulator hss;
    private final com.mobius.software.telco.protocols.diameter.app.s6c.MessageFactory messages;

    S6cHandler(HssSimulator hss, com.mobius.software.telco.protocols.diameter.app.s6c.MessageFactory messages) {
        this.hss = hss;
        this.messages = messages;
    }

    @Override
    public void onInitialRequest(S6cRequest request,
            ServerAuthSessionStateless<S6cAnswer> session, String linkID,
            AsyncCallback callback) {
        try {
            S6cAnswer answer = build(request);
            tagVendorAppId(answer, S6C_APP_ID);
            session.sendInitialAnswer(answer, callback);
        } catch (Exception e) {
            LOG.warn("S6c handler failure", e);
            try {
                S6cAnswer fallback = buildAnswer(request, Answers.UNABLE_TO_DELIVER);
                tagVendorAppId(fallback, S6C_APP_ID);
                session.sendInitialAnswer(fallback, callback);
            } catch (Exception fatal) {
                LOG.error("S6c fail-safe answer failed", fatal);
                callback.onError(new DiameterException("handler failure", null,
                        Answers.UNABLE_TO_DELIVER, null));
            }
        }
    }

    private S6cAnswer build(S6cRequest request) throws Exception {
        if (request instanceof SendRoutingInfoForSMRequest srr) {
            Answers.received(hss, "SRR", srr, "user=" + Answers.usernameOf(srr));
            return buildAnswer(srr, Answers.SUCCESS);
        }
        if (request instanceof AlertServiceCentreRequest asc) {
            Answers.received(hss, "ASC", asc, "user=" + Answers.usernameOf(asc));
            return buildAnswer(asc, Answers.SUCCESS);
        }
        return buildAnswer(request, Answers.UNABLE_TO_DELIVER);
    }

    private S6cAnswer buildAnswer(S6cRequest request, long resultCode) throws Exception {
        if (request instanceof SendRoutingInfoForSMRequest srr) {
            return messages.createSendRoutingInfoForSMAnswer(srr,
                    srr.getHopByHopIdentifier(), srr.getEndToEndIdentifier(), resultCode);
        }
        if (request instanceof AlertServiceCentreRequest asc) {
            return messages.createAlertServiceCentreAnswer(asc,
                    asc.getHopByHopIdentifier(), asc.getEndToEndIdentifier(), resultCode);
        }
        throw new IllegalStateException("unsupported S6c command");
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
        LOG.warn("S6c server timeout session={}", session == null ? "?" : session.getID());
    }

    @Override
    public void onIdleTimeout(com.mobius.software.telco.protocols.diameter.DiameterSession session) {
    }
}
