package et.elisa.iwf.sbb;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;

import com.microjainslee.api.RaCommandPort;
import com.mobius.software.telco.protocols.diameter.ResultCodes;
import com.mobius.software.telco.protocols.diameter.commands.DiameterAnswer;
import com.mobius.software.telco.protocols.diameter.commands.DiameterRequest;
import com.mobius.software.telco.protocols.diameter.commands.commons.VendorSpecificAnswer;
import com.mobius.software.telco.protocols.diameter.impl.commands.s6a.CancelLocationAnswerImpl;
import com.mobius.software.telco.protocols.diameter.impl.commands.s6a.DeleteSubscriberDataAnswerImpl;
import com.mobius.software.telco.protocols.diameter.impl.commands.s6a.InsertSubscriberDataAnswerImpl;
import com.mobius.software.telco.protocols.diameter.impl.commands.s6a.NotifyAnswerImpl;
import com.mobius.software.telco.protocols.diameter.parser.DiameterParser;
import com.mobius.software.telco.protocols.diameter.impl.primitives.common.VendorSpecificApplicationIdImpl;
import com.mobius.software.telco.protocols.diameter.primitives.common.AuthSessionStateEnum;

import et.elisa.iwf.IwfConfig;
import et.elisa.iwf.diameter.DiaCmd;
import et.elisa.iwf.mapping.DialogBindingRegistry;
import et.elisa.iwf.ra.IwfSendCommand;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * IWF relay core — the SINGLE entry point behind the SBB. Completes
 * leg-pending answers and answers server-initiated S6a requests fail-loud
 * with DIAMETER_UNABLE_TO_COMPLY until the MAP leg lands (M-IWF-3).
 * Never fan ingress out to multiple handlers (DRA triple-processing lesson).
 */
public final class IwfRelayCore {

    private static final Logger LOG = LogManager.getLogger(IwfRelayCore.class);

    private final IwfConfig.DiaLegConfig config;
    private final ConcurrentMap<Long, CompletableFuture<DiameterAnswer>> pending;
    private final LongAdder answersCorrelated = new LongAdder();
    private final LongAdder serverInitiated = new LongAdder();
    private final LongAdder serverInitiatedAnswered = new LongAdder();
    private volatile RaCommandPort commandPort;
    private volatile DialogBindingRegistry bindingRegistry;

    public IwfRelayCore(IwfConfig.DiaLegConfig config,
                        ConcurrentMap<Long, CompletableFuture<DiameterAnswer>> pending) {
        this.config = Objects.requireNonNull(config, "config");
        this.pending = Objects.requireNonNull(pending, "pending");
    }

    /** Called by the SBB once the container injects the RA command port. */
    public void bindCommandPort(RaCommandPort port) {
        this.commandPort = port;
    }

    public void setBindingRegistry(DialogBindingRegistry registry) {
        this.bindingRegistry = registry;
    }

    public long answersCorrelated() {
        return answersCorrelated.sum();
    }

    public long serverInitiated() {
        return serverInitiated.sum();
    }

    public long serverInitiatedAnswered5012() {
        return serverInitiatedAnswered.sum();
    }

    public void onAnswer(String linkId, long hopByHopId, DiameterAnswer answer) {
        CompletableFuture<DiameterAnswer> future = pending.remove(hopByHopId);
        if (future != null) {
            answersCorrelated.increment();
            future.complete(answer);
        } else {
            LOG.debug("[iwf-core] answer without pending tx hbh={} link={}",
                    hopByHopId, linkId);
        }
    }

    /**
     * Server-initiated S6a (CLR/IDR/DSR/NOR): MAP leg not wired yet — answer
     * 5012 carrying OUR origin (withOrigin law) instead of dropping silently.
     * When the binding registry has an active IMSI→dialog mapping, it is
     * logged so M-IWF-3 can route to the correct TCAP dialog.
     */
    public void onRequest(String linkId, DiameterRequest request) {
        serverInitiated.increment();
        int cmdCode = DiameterParser.getCommandDefinition(request.getClass()).commandCode();
        DiaCmd diaCmd = switch (cmdCode) {
            case 317 -> DiaCmd.CLR;
            case 319 -> DiaCmd.IDR;
            case 320 -> DiaCmd.DSR;
            case 323 -> DiaCmd.NOR;
            default -> null;
        };
        String imsi = safeImsi(request);
        DialogBindingRegistry.Binding binding = null;
        DialogBindingRegistry reg = bindingRegistry;
        if (reg != null && imsi != null) {
            binding = reg.lookup(imsi).orElse(null);
        }
        LOG.info("[iwf-core] server-initiated cmd={} imsi={} binding={} (answer 5012 until MAP leg wired M-IWF-3)",
                cmdCode, imsi, binding == null ? "none" : binding);
        try {
            DiameterAnswer answer = diaCmd == null ? null : switch (diaCmd) {
                case CLR -> new CancelLocationAnswerImpl(
                        config.originHost(), config.originRealm(), Boolean.FALSE,
                        ResultCodes.DIAMETER_UNABLE_TO_COMPLY,
                        safeSessionId(request), AuthSessionStateEnum.STATE_MAINTAINED);
                case IDR -> new InsertSubscriberDataAnswerImpl(
                        config.originHost(), config.originRealm(), Boolean.FALSE,
                        ResultCodes.DIAMETER_UNABLE_TO_COMPLY,
                        safeSessionId(request), AuthSessionStateEnum.STATE_MAINTAINED);
                case DSR -> new DeleteSubscriberDataAnswerImpl(
                        config.originHost(), config.originRealm(), Boolean.FALSE,
                        ResultCodes.DIAMETER_UNABLE_TO_COMPLY,
                        safeSessionId(request), AuthSessionStateEnum.STATE_MAINTAINED);
                case NOR -> new NotifyAnswerImpl(
                        config.originHost(), config.originRealm(), Boolean.FALSE,
                        ResultCodes.DIAMETER_UNABLE_TO_COMPLY,
                        safeSessionId(request), AuthSessionStateEnum.STATE_MAINTAINED);
                default -> null;
            };
            if (answer == null) {
                LOG.warn("[iwf-core] server-initiated cmd={} dropped (no answer mapping)", cmdCode);
                return;
            }
            answer.setHopByHopIdentifier(request.getHopByHopIdentifier());
            answer.setEndToEndIdentifier(request.getEndToEndIdentifier());
            tagVendorS6a(answer);
            RaCommandPort port = commandPort;
            if (port == null) {
                LOG.warn("[iwf-core] no RA command port bound — cannot answer cmd={}", cmdCode);
                return;
            }
            port.sendCommand(new IwfSendCommand(linkId, answer));
            serverInitiatedAnswered.increment();
            LOG.warn("[iwf-core] server-initiated cmd={} answered 5012 (MAP leg: M-IWF-3; will dispatch via IwfEngine.diameterToMap)", cmdCode);
        } catch (Exception e) {
            LOG.warn("[iwf-core] failed answering inbound cmd={}: {}", cmdCode, e.toString());
        }
    }

    /** corsac canSendMessage() needs the VSA AVP on every outbound message. */
    private static void tagVendorS6a(DiameterAnswer answer) throws Exception {
        if (answer instanceof VendorSpecificAnswer vsa) {
            var vendorAppId = new VendorSpecificApplicationIdImpl();
            vendorAppId.setAuthApplicationId(DiaCmd.S6A_APP_ID);
            vsa.setVendorSpecificApplicationId(vendorAppId);
        }
    }

    private static String safeSessionId(DiameterRequest request) {
        try {
            String sid = request.getSessionId();
            return sid == null ? "" : sid;
        } catch (Exception e) {
            return "";
        }
    }

    private static String safeImsi(DiameterRequest request) {
        try {
            String imsi = request.getUsername();
            return (imsi == null || imsi.isBlank()) ? null : imsi;
        } catch (Exception e) {
            return null;
        }
    }
}
