package et.elisa.dra.lab.testapp.diameter;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.mobius.software.telco.protocols.diameter.AsyncCallback;
import com.mobius.software.telco.protocols.diameter.app.ServerAuthSession;
import com.mobius.software.telco.protocols.diameter.commands.DiameterRequest;
import com.mobius.software.telco.protocols.diameter.commands.rx.AARequest;
import com.mobius.software.telco.protocols.diameter.commands.rx.AAAnswer;
import com.mobius.software.telco.protocols.diameter.commands.rx.ReAuthRequest;
import com.mobius.software.telco.protocols.diameter.commands.rx.ReAuthAnswer;
import com.mobius.software.telco.protocols.diameter.commands.rx.AbortSessionRequest;
import com.mobius.software.telco.protocols.diameter.commands.rx.AbortSessionAnswer;
import com.mobius.software.telco.protocols.diameter.commands.rx.SessionTerminationRequest;
import com.mobius.software.telco.protocols.diameter.commands.rx.SessionTerminationAnswer;
import com.mobius.software.telco.protocols.diameter.exceptions.DiameterException;
import com.mobius.software.telco.protocols.diameter.primitives.common.AuthRequestTypeEnum;

import et.elisa.dra.lab.testapp.HssSimulator;

final class RxHandler implements com.mobius.software.telco.protocols.diameter.app.rx.ServerListener {

    private static final Logger LOG = LogManager.getLogger(RxHandler.class);

    private final HssSimulator hss;
    private final com.mobius.software.telco.protocols.diameter.app.rx.MessageFactory messages;

    RxHandler(HssSimulator hss, com.mobius.software.telco.protocols.diameter.app.rx.MessageFactory messages) {
        this.hss = hss;
        this.messages = messages;
    }

    @Override
    public void onInitialRequest(AARequest request,
            ServerAuthSession<AAAnswer, ReAuthRequest, AbortSessionRequest,
                    SessionTerminationAnswer> session, String linkID,
            AsyncCallback callback) {
        try {
            Answers.received(hss, "AAR", request, "session=" + Answers.sessionId(request));
            AAAnswer answer = messages.createAAAnswer(request,
                    request.getHopByHopIdentifier(), request.getEndToEndIdentifier(),
                    Answers.SUCCESS, AuthRequestTypeEnum.AUTHORIZE_AUTHENTICATE);
            session.sendInitialAnswer(answer, callback);
        } catch (Exception e) {
            LOG.warn("Rx handler failure", e);
            try {
                AAAnswer fallback = messages.createAAAnswer(request,
                        request.getHopByHopIdentifier(), request.getEndToEndIdentifier(),
                        Answers.UNABLE_TO_DELIVER, AuthRequestTypeEnum.AUTHORIZE_AUTHENTICATE);
                session.sendInitialAnswer(fallback, callback);
            } catch (Exception fatal) {
                LOG.error("Rx fail-safe answer failed", fatal);
                callback.onError(new DiameterException("handler failure", null,
                        Answers.UNABLE_TO_DELIVER, null));
            }
        }
    }

    @Override
    public void onReauthAnswer(ReAuthAnswer answer,
            ServerAuthSession<AAAnswer, ReAuthRequest, AbortSessionRequest,
                    SessionTerminationAnswer> session, String linkID,
            AsyncCallback callback) {
    }

    @Override
    public void onSessionTerminationRequest(SessionTerminationRequest request,
            ServerAuthSession<AAAnswer, ReAuthRequest, AbortSessionRequest,
                    SessionTerminationAnswer> session, String linkID,
            AsyncCallback callback) {
        LOG.warn("Rx STR in lab simulator");
    }

    @Override
    public void onAbortSessionAnswer(AbortSessionAnswer answer,
            ServerAuthSession<AAAnswer, ReAuthRequest, AbortSessionRequest,
                    SessionTerminationAnswer> session, String linkID,
            AsyncCallback callback) {
    }

    @Override
    public void onTimeout(DiameterRequest request,
            com.mobius.software.telco.protocols.diameter.DiameterSession session) {
        LOG.warn("Rx server timeout session={}", session == null ? "?" : session.getID());
    }

    @Override
    public void onIdleTimeout(com.mobius.software.telco.protocols.diameter.DiameterSession session) {
    }
}
