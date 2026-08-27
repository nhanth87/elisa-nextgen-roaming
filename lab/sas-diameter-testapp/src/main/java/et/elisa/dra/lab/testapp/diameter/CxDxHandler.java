package et.elisa.dra.lab.testapp.diameter;

import java.time.Instant;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.mobius.software.telco.protocols.diameter.AsyncCallback;
import com.mobius.software.telco.protocols.diameter.app.ServerAuthSessionStateless;
import com.mobius.software.telco.protocols.diameter.commands.DiameterRequest;
import com.mobius.software.telco.protocols.diameter.commands.cxdx.CxDxRequest;
import com.mobius.software.telco.protocols.diameter.commands.cxdx.CxDxAnswer;
import com.mobius.software.telco.protocols.diameter.commands.cxdx.UserAuthorizationRequest;
import com.mobius.software.telco.protocols.diameter.commands.cxdx.UserAuthorizationAnswer;
import com.mobius.software.telco.protocols.diameter.commands.cxdx.LocationInfoRequest;
import com.mobius.software.telco.protocols.diameter.commands.cxdx.LocationInfoAnswer;
import com.mobius.software.telco.protocols.diameter.commands.cxdx.ServerAssignmentRequest;
import com.mobius.software.telco.protocols.diameter.commands.cxdx.ServerAssignmentAnswer;
import com.mobius.software.telco.protocols.diameter.exceptions.DiameterException;

import et.elisa.dra.lab.testapp.HssSimulator;

final class CxDxHandler implements com.mobius.software.telco.protocols.diameter.app.cxdx.ServerListener {

    private static final long CXDX_APP_ID = 16777216L;
    private static final Logger LOG = LogManager.getLogger(CxDxHandler.class);

    private final HssSimulator hss;
    private final com.mobius.software.telco.protocols.diameter.app.cxdx.MessageFactory messages;

    CxDxHandler(HssSimulator hss, com.mobius.software.telco.protocols.diameter.app.cxdx.MessageFactory messages) {
        this.hss = hss;
        this.messages = messages;
    }

    @Override
    public void onInitialRequest(CxDxRequest request,
            ServerAuthSessionStateless<CxDxAnswer> session, String linkID,
            AsyncCallback callback) {
        try {
            CxDxAnswer answer = build(request);
            tagVendorAppId(answer, CXDX_APP_ID);
            session.sendInitialAnswer(answer, callback);
        } catch (Exception e) {
            LOG.warn("CxDx handler failure", e);
            try {
                CxDxAnswer fallback = buildAnswer(request, Answers.UNABLE_TO_DELIVER);
                tagVendorAppId(fallback, CXDX_APP_ID);
                session.sendInitialAnswer(fallback, callback);
            } catch (Exception fatal) {
                LOG.error("CxDx fail-safe answer failed", fatal);
                callback.onError(new DiameterException("handler failure", null,
                        Answers.UNABLE_TO_DELIVER, null));
            }
        }
    }

    private CxDxAnswer build(CxDxRequest request) throws Exception {
        if (request instanceof UserAuthorizationRequest uar) {
            Answers.received(hss, "UAR", uar, "user=" + Answers.usernameOf(uar));
            return buildAnswer(uar, Answers.SUCCESS);
        }
        if (request instanceof LocationInfoRequest lir) {
            Answers.received(hss, "LIR", lir, "user=" + Answers.usernameOf(lir));
            return buildAnswer(lir, Answers.SUCCESS);
        }
        if (request instanceof ServerAssignmentRequest sar) {
            Answers.received(hss, "SAR", sar, "user=" + Answers.usernameOf(sar));
            return buildAnswer(sar, Answers.SUCCESS);
        }
        return buildAnswer(request, Answers.UNABLE_TO_DELIVER);
    }

    private CxDxAnswer buildAnswer(CxDxRequest request, long resultCode) throws Exception {
        if (request instanceof UserAuthorizationRequest uar) {
            return messages.createUserAuthorizationAnswer(uar,
                    uar.getHopByHopIdentifier(), uar.getEndToEndIdentifier(), resultCode);
        }
        if (request instanceof LocationInfoRequest lir) {
            return messages.createLocationInfoAnswer(lir,
                    lir.getHopByHopIdentifier(), lir.getEndToEndIdentifier(), resultCode);
        }
        if (request instanceof ServerAssignmentRequest sar) {
            return messages.createServerAssignmentAnswer(sar,
                    sar.getHopByHopIdentifier(), sar.getEndToEndIdentifier(), resultCode);
        }
        throw new IllegalStateException("unsupported CxDx command");
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
        LOG.warn("CxDx server timeout session={}", session == null ? "?" : session.getID());
    }

    @Override
    public void onIdleTimeout(com.mobius.software.telco.protocols.diameter.DiameterSession session) {
    }
}
