package et.elisa.dra.lab.testapp.diameter;

import java.time.Instant;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.mobius.software.telco.protocols.diameter.AsyncCallback;
import com.mobius.software.telco.protocols.diameter.app.ServerAuthSessionStateless;
import com.mobius.software.telco.protocols.diameter.commands.DiameterRequest;
import com.mobius.software.telco.protocols.diameter.commands.slg.SlgRequest;
import com.mobius.software.telco.protocols.diameter.commands.slg.SlgAnswer;
import com.mobius.software.telco.protocols.diameter.commands.slg.ProvideLocationRequest;
import com.mobius.software.telco.protocols.diameter.commands.slg.ProvideLocationAnswer;
import com.mobius.software.telco.protocols.diameter.commands.slg.LocationReportRequest;
import com.mobius.software.telco.protocols.diameter.commands.slg.LocationReportAnswer;
import com.mobius.software.telco.protocols.diameter.exceptions.DiameterException;

import et.elisa.dra.lab.testapp.HssSimulator;

final class SLgHandler implements com.mobius.software.telco.protocols.diameter.app.slg.ServerListener {

    private static final long SLG_APP_ID = 16777255L;
    private static final Logger LOG = LogManager.getLogger(SLgHandler.class);

    private final HssSimulator hss;
    private final com.mobius.software.telco.protocols.diameter.app.slg.MessageFactory messages;

    SLgHandler(HssSimulator hss, com.mobius.software.telco.protocols.diameter.app.slg.MessageFactory messages) {
        this.hss = hss;
        this.messages = messages;
    }

    @Override
    public void onInitialRequest(SlgRequest request,
            ServerAuthSessionStateless<SlgAnswer> session, String linkID,
            AsyncCallback callback) {
        try {
            SlgAnswer answer = build(request);
            tagVendorAppId(answer, SLG_APP_ID);
            session.sendInitialAnswer(answer, callback);
        } catch (Exception e) {
            LOG.warn("SLg handler failure", e);
            try {
                SlgAnswer fallback = buildAnswer(request, Answers.UNABLE_TO_DELIVER);
                tagVendorAppId(fallback, SLG_APP_ID);
                session.sendInitialAnswer(fallback, callback);
            } catch (Exception fatal) {
                LOG.error("SLg fail-safe answer failed", fatal);
                callback.onError(new DiameterException("handler failure", null,
                        Answers.UNABLE_TO_DELIVER, null));
            }
        }
    }

    private SlgAnswer build(SlgRequest request) throws Exception {
        if (request instanceof ProvideLocationRequest plr) {
            Answers.received(hss, "PLR", plr, "user=" + Answers.usernameOf(plr));
            return buildAnswer(plr, Answers.SUCCESS);
        }
        if (request instanceof LocationReportRequest lrr) {
            Answers.received(hss, "LRR", lrr, "event=" + lrr.getLocationEvent());
            return buildAnswer(lrr, Answers.SUCCESS);
        }
        return buildAnswer(request, Answers.UNABLE_TO_DELIVER);
    }

    private SlgAnswer buildAnswer(SlgRequest request, long resultCode) throws Exception {
        if (request instanceof ProvideLocationRequest plr) {
            return messages.createProvideLocationAnswer(plr,
                    plr.getHopByHopIdentifier(), plr.getEndToEndIdentifier(), resultCode);
        }
        if (request instanceof LocationReportRequest lrr) {
            return messages.createLocationReportAnswer(lrr,
                    lrr.getHopByHopIdentifier(), lrr.getEndToEndIdentifier(), resultCode);
        }
        throw new IllegalStateException("unsupported SLg command");
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
        LOG.warn("SLg server timeout session={}", session == null ? "?" : session.getID());
    }

    @Override
    public void onIdleTimeout(com.mobius.software.telco.protocols.diameter.DiameterSession session) {
    }
}
