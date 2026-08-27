package et.elisa.dra.lab.testapp.diameter;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.mobius.software.telco.protocols.diameter.AsyncCallback;
import com.mobius.software.telco.protocols.diameter.app.ServerAuthSessionStateless;
import com.mobius.software.telco.protocols.diameter.commands.DiameterRequest;
import com.mobius.software.telco.protocols.diameter.commands.sh.ShRequest;
import com.mobius.software.telco.protocols.diameter.commands.sh.ShAnswer;
import com.mobius.software.telco.protocols.diameter.commands.sh.UserDataRequest;
import com.mobius.software.telco.protocols.diameter.commands.sh.UserDataAnswer;
import com.mobius.software.telco.protocols.diameter.commands.sh.SubscribeNotificationsRequest;
import com.mobius.software.telco.protocols.diameter.commands.sh.SubscribeNotificationsAnswer;
import com.mobius.software.telco.protocols.diameter.commands.sh.ProfileUpdateRequest;
import com.mobius.software.telco.protocols.diameter.commands.sh.ProfileUpdateAnswer;
import com.mobius.software.telco.protocols.diameter.exceptions.DiameterException;

import et.elisa.dra.lab.testapp.HssSimulator;

final class ShHandler implements com.mobius.software.telco.protocols.diameter.app.sh.ServerListener {

    private static final long SH_APP_ID = 16777217L;
    private static final Logger LOG = LogManager.getLogger(ShHandler.class);

    private final HssSimulator hss;
    private final com.mobius.software.telco.protocols.diameter.app.sh.MessageFactory messages;

    ShHandler(HssSimulator hss, com.mobius.software.telco.protocols.diameter.app.sh.MessageFactory messages) {
        this.hss = hss;
        this.messages = messages;
    }

    @Override
    public void onInitialRequest(ShRequest request,
            ServerAuthSessionStateless<ShAnswer> session, String linkID,
            AsyncCallback callback) {
        try {
            ShAnswer answer = build(request);
            tagVendorAppId(answer, SH_APP_ID);
            session.sendInitialAnswer(answer, callback);
        } catch (Exception e) {
            LOG.warn("Sh handler failure on {}", request.getClass().getSimpleName(), e);
            try {
                ShAnswer fallback = buildAnswer(request, Answers.UNABLE_TO_DELIVER);
                tagVendorAppId(fallback, SH_APP_ID);
                session.sendInitialAnswer(fallback, callback);
            } catch (Exception fatal) {
                LOG.error("Sh fail-safe answer failed", fatal);
                callback.onError(new DiameterException("handler failure", null,
                        Answers.UNABLE_TO_DELIVER, null));
            }
        }
    }

    private ShAnswer build(ShRequest request) throws Exception {
        if (request instanceof UserDataRequest udr) {
            Answers.received(hss, "UDR", udr, "user=" + Answers.usernameOf(udr));
            return buildAnswer(udr, Answers.SUCCESS);
        }
        if (request instanceof SubscribeNotificationsRequest snr) {
            Answers.received(hss, "SNR", snr, "user=" + Answers.usernameOf(snr));
            return buildAnswer(snr, Answers.SUCCESS);
        }
        if (request instanceof ProfileUpdateRequest pur) {
            Answers.received(hss, "PUR", pur, "user=" + Answers.usernameOf(pur));
            return buildAnswer(pur, Answers.SUCCESS);
        }
        return buildAnswer(request, Answers.UNABLE_TO_DELIVER);
    }

    private ShAnswer buildAnswer(ShRequest request, long resultCode) throws Exception {
        if (request instanceof UserDataRequest udr) {
            return messages.createUserDataAnswer(udr,
                    udr.getHopByHopIdentifier(), udr.getEndToEndIdentifier(), resultCode);
        }
        if (request instanceof SubscribeNotificationsRequest snr) {
            return messages.createSubscribeNotificationsAnswer(snr,
                    snr.getHopByHopIdentifier(), snr.getEndToEndIdentifier(), resultCode);
        }
        if (request instanceof ProfileUpdateRequest pur) {
            return messages.createProfileUpdateAnswer(pur,
                    pur.getHopByHopIdentifier(), pur.getEndToEndIdentifier(), resultCode);
        }
        throw new IllegalStateException("unsupported Sh command");
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
        LOG.warn("Sh server timeout session={}", session == null ? "?" : session.getID());
    }

    @Override
    public void onIdleTimeout(com.mobius.software.telco.protocols.diameter.DiameterSession session) {
    }
}
