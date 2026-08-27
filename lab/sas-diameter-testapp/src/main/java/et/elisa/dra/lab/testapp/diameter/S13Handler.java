package et.elisa.dra.lab.testapp.diameter;

import java.time.Instant;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.mobius.software.telco.protocols.diameter.AsyncCallback;
import com.mobius.software.telco.protocols.diameter.app.ServerAuthSessionStateless;
import com.mobius.software.telco.protocols.diameter.commands.DiameterRequest;
import com.mobius.software.telco.protocols.diameter.commands.s13.MEIdentityCheckRequest;
import com.mobius.software.telco.protocols.diameter.commands.s13.MEIdentityCheckAnswer;
import com.mobius.software.telco.protocols.diameter.exceptions.DiameterException;

import et.elisa.dra.lab.testapp.HssSimulator;

final class S13Handler implements com.mobius.software.telco.protocols.diameter.app.s13.ServerListener {

    private static final long S13_APP_ID = 16777252L;
    private static final Logger LOG = LogManager.getLogger(S13Handler.class);

    private final HssSimulator hss;
    private final com.mobius.software.telco.protocols.diameter.app.s13.MessageFactory messages;

    S13Handler(HssSimulator hss, com.mobius.software.telco.protocols.diameter.app.s13.MessageFactory messages) {
        this.hss = hss;
        this.messages = messages;
    }

    @Override
    public void onInitialRequest(MEIdentityCheckRequest request,
            ServerAuthSessionStateless<MEIdentityCheckAnswer> session, String linkID,
            AsyncCallback callback) {
        try {
            Answers.received(hss, "ECR", request, "user=" + Answers.usernameOf(request));
            MEIdentityCheckAnswer answer = messages.createMEIdentityCheckAnswer(request,
                    request.getHopByHopIdentifier(), request.getEndToEndIdentifier(),
                    Answers.SUCCESS);
            tagVendorAppId(answer, S13_APP_ID);
            session.sendInitialAnswer(answer, callback);
        } catch (Exception e) {
            LOG.warn("S13 handler failure", e);
            try {
                MEIdentityCheckAnswer fallback = messages.createMEIdentityCheckAnswer(request,
                        request.getHopByHopIdentifier(), request.getEndToEndIdentifier(),
                        Answers.UNABLE_TO_DELIVER);
                tagVendorAppId(fallback, S13_APP_ID);
                session.sendInitialAnswer(fallback, callback);
            } catch (Exception fatal) {
                LOG.error("S13 fail-safe answer failed", fatal);
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
        LOG.warn("S13 server timeout session={}", session == null ? "?" : session.getID());
    }

    @Override
    public void onIdleTimeout(com.mobius.software.telco.protocols.diameter.DiameterSession session) {
    }
}
