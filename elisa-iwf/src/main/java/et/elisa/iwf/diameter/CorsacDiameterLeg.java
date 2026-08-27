package et.elisa.iwf.diameter;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import et.elisa.iwf.IwfConfig;
import et.elisa.iwf.diameter.linkreg.LinkRegMarker;
import et.elisa.iwf.mapping.AvpTransform;
import et.elisa.iwf.mapping.DialogBindingRegistry;
import et.elisa.iwf.mapping.IwfEngine;
import et.elisa.iwf.ra.IwfRaEndpoint;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import com.mobius.software.common.dal.timers.WorkerPool;
import com.mobius.software.telco.protocols.diameter.AsyncCallback;
import com.mobius.software.telco.protocols.diameter.ApplicationIDs;
import com.mobius.software.telco.protocols.diameter.DiameterLink;
import com.mobius.software.telco.protocols.diameter.DiameterStack;
import com.mobius.software.telco.protocols.diameter.NetworkListener;
import com.mobius.software.telco.protocols.diameter.PeerStateEnum;
import com.mobius.software.telco.protocols.diameter.ResultCodes;
import com.mobius.software.telco.protocols.diameter.commands.DiameterAnswer;
import com.mobius.software.telco.protocols.diameter.commands.DiameterMessage;
import com.mobius.software.telco.protocols.diameter.commands.DiameterRequest;
import com.mobius.software.telco.protocols.diameter.commands.commons.CapabilitiesExchangeAnswer;
import com.mobius.software.telco.protocols.diameter.commands.commons.CapabilitiesExchangeRequest;
import com.mobius.software.telco.protocols.diameter.commands.commons.DeviceWatchdogAnswer;
import com.mobius.software.telco.protocols.diameter.commands.commons.DeviceWatchdogRequest;
import com.mobius.software.telco.protocols.diameter.commands.commons.DisconnectPeerAnswer;
import com.mobius.software.telco.protocols.diameter.commands.commons.DisconnectPeerRequest;
import com.mobius.software.telco.protocols.diameter.commands.s6a.AuthenticationInformationRequest;
import com.mobius.software.telco.protocols.diameter.commands.s6a.CancelLocationAnswer;
import com.mobius.software.telco.protocols.diameter.commands.s6a.DeleteSubscriberDataAnswer;
import com.mobius.software.telco.protocols.diameter.commands.s6a.InsertSubscriberDataAnswer;
import com.mobius.software.telco.protocols.diameter.commands.s6a.NotifyAnswer;
import com.mobius.software.telco.protocols.diameter.commands.s6a.NotifyRequest;
import com.mobius.software.telco.protocols.diameter.commands.s6a.PurgeUERequest;
import com.mobius.software.telco.protocols.diameter.commands.s6a.UpdateLocationRequest;
import com.mobius.software.telco.protocols.diameter.exceptions.DiameterException;
import com.mobius.software.telco.protocols.diameter.impl.DiameterStackImpl;
import com.mobius.software.telco.protocols.diameter.impl.commands.s6a.AuthenticationInformationRequestImpl;
import com.mobius.software.telco.protocols.diameter.impl.commands.s6a.CancelLocationAnswerImpl;
import com.mobius.software.telco.protocols.diameter.impl.commands.s6a.DeleteSubscriberDataAnswerImpl;
import com.mobius.software.telco.protocols.diameter.impl.commands.s6a.InsertSubscriberDataAnswerImpl;
import com.mobius.software.telco.protocols.diameter.impl.commands.s6a.NotifyAnswerImpl;
import com.mobius.software.telco.protocols.diameter.impl.commands.s6a.NotifyRequestImpl;
import com.mobius.software.telco.protocols.diameter.impl.commands.s6a.PurgeUERequestImpl;
import com.mobius.software.telco.protocols.diameter.impl.commands.s6a.UpdateLocationRequestImpl;
import com.mobius.software.telco.protocols.diameter.impl.primitives.s6a.RequestedEUTRANAuthenticationInfoImpl;
import com.mobius.software.telco.protocols.diameter.impl.primitives.s6a.ULRFlagsImpl;
import com.mobius.software.telco.protocols.diameter.primitives.common.AuthSessionStateEnum;
import com.mobius.software.telco.protocols.diameter.primitives.gx.RATTypeEnum;

import io.netty.buffer.Unpooled;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Real Diameter client leg over the corsac (mobius) stack: SCTP to the DRA,
 * CER/CEA + watchdog handled by the stack, typed S6a/S6d requests with
 * hop-by-hop correlation. Peer-truth law applies: ready() == transport up
 * AND PeerStateEnum.OPEN, mirrored from live link state every 100 ms with a
 * synchronous re-check before failing traffic (readiness-race lesson).
 */
public final class CorsacDiameterLeg implements DiameterLeg, AutoCloseable {

    public static final String PRODUCT_NAME = "elisa-iwf";
    static final String LINK_ID = "dra-link";
    static final int WORKER_THREADS = 4;
    static final long LINK_POLL_MILLIS = 100L;

    private static final Logger LOG = LogManager.getLogger(CorsacDiameterLeg.class);

    private final IwfConfig.DiaLegConfig config;
    private final IwfEngine engine = new IwfEngine();
    private volatile DialogBindingRegistry bindingRegistry;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final ConcurrentMap<Long, CompletableFuture<DiameterAnswer>> pending =
            new ConcurrentHashMap<>();
    private final AtomicLong hopByHopSeq = new AtomicLong(1);
    private final AtomicLong endToEndSeq = new AtomicLong(0);
    private final AtomicLong sessionSeq = new AtomicLong(0);
    private final LongAdder sent = new LongAdder();
    private final LongAdder answered = new LongAdder();
    private final LongAdder timedOut = new LongAdder();
    private final LongAdder inboundRequests = new LongAdder();
    private final LongAdder inboundAnswered = new LongAdder();

    private volatile boolean linkOpen;
    private volatile WorkerPool workerPool;
    private volatile DiameterStack stack;
    private volatile ScheduledExecutorService linkWatcher;
    private volatile IwfRaEndpoint endpoint;

    public CorsacDiameterLeg(IwfConfig.DiaLegConfig config) {
        Objects.requireNonNull(config, "config");
        this.config = config;
    }

    public synchronized void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        try {
            workerPool = new WorkerPool("iwf-dia");
            workerPool.start(WORKER_THREADS);
            stack = new DiameterStackImpl(getClass().getClassLoader(),
                    new org.restcomm.cluster.UUIDGenerator(),
                    workerPool,
                    WORKER_THREADS,
                    config.originHost(),
                    PRODUCT_NAME,
                    0L,
                    10L,
                    120_000L,
                    60_000L,
                    2_000L,
                    0L,
                    0L);
            var nm = stack.getNetworkManager();
            registerCommandPackages();
            nm.addNetworkListener("iwf-dia-ingress", this::onCorsacIngress);

            InetAddress remote = InetAddress.getByName(config.draHost());
            InetAddress local = remote.isLoopbackAddress()
                    ? remote : InetAddress.getByName("0.0.0.0");
            nm.addLink(LINK_ID, remote, config.draPort(), local, config.srcPort(),
                    Boolean.FALSE, Boolean.TRUE,
                    config.originHost(), config.originRealm(),
                    config.destHost(), config.destRealm(),
                    Boolean.FALSE, Boolean.FALSE);
            nm.registerApplication(LINK_ID,
                    List.of(),
                    List.of((long) ApplicationIDs.S6A),
                    List.of((long) ApplicationIDs.ACCOUNTING),
                    LinkRegMarker.class.getPackage(),
                    LinkRegMarker.class.getPackage());
            registerLinkDecodePackages();
            nm.startLink(LINK_ID);

            linkWatcher = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "iwf-dia-watch");
                t.setDaemon(true);
                return t;
            });
            linkWatcher.scheduleWithFixedDelay(this::pollLinks,
                    LINK_POLL_MILLIS, LINK_POLL_MILLIS, TimeUnit.MILLISECONDS);
            LOG.info("[iwf-dia] corsac leg started: {}:{} src={} origin={} target-open-pending "
                            + "(LISTEN != OPEN)",
                    config.draHost(), config.draPort(), config.srcPort(), config.originHost());
        } catch (Exception e) {
            teardownQuietly();
            started.set(false);
            throw new IllegalStateException("corsac diameter leg failed to start", e);
        }
    }

    public synchronized void stop() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        teardownQuietly();
        failAllPending("leg stopped");
        LOG.info("[iwf-dia] corsac leg stopped");
    }

    @Override
    public void close() {
        stop();
    }

    @Override
    public boolean ready() {
        if (!started.get()) {
            return false;
        }
        if (!linkOpen) {
            mirrorLinkStateOnce();
        }
        return linkOpen;
    }

    @Override
    public DiaResult send(DiaCmd cmd, Map<String, String> avps) throws DiaLegException {
        Objects.requireNonNull(avps, "avps");
        if (!started.get()) {
            throw new DiaLegException("leg not started");
        }
        String sessionId = config.originHost() + ";" + sessionSeq.incrementAndGet();
        long hbh = hopByHopSeq.incrementAndGet();
        DiameterRequest request;
        try {
            request = buildRequest(cmd, avps, sessionId);
            request.setHopByHopIdentifier(hbh);
            request.setEndToEndIdentifier(endToEndSeq.incrementAndGet());
        } catch (DiaLegException e) {
            throw e;
        } catch (Exception e) {
            throw new DiaLegException("cannot build " + cmd + ": " + e.getMessage(), e);
        }

        var future = new CompletableFuture<DiameterAnswer>();
        pending.put(hbh, future);
        DiameterLink link = link();
        if (link == null || !linkOpen) {
            mirrorLinkStateOnce();
        }
        try {
            if (link == null || !link.isConnected()) {
                throw new DiaLegException("no live transport link to DRA "
                        + config.draHost() + ":" + config.draPort());
            }
            sent.increment();
            link.sendMessage(request, noop());
            DialogBindingRegistry reg = bindingRegistry;
            if (reg != null) {
                reg.bind(avps.get("imsi"), sessionId, hbh, null);
            }
        } catch (RuntimeException e) {
            pending.remove(hbh);
            throw new DiaLegException("send " + cmd + " failed: " + e.getMessage(), e);
        } catch (DiaLegException e) {
            pending.remove(hbh);
            throw e;
        }

        try {
            DiameterAnswer answer = future.get(config.responseTimeoutMillis(),
                    TimeUnit.MILLISECONDS);
            answered.increment();
            return new DiaResult(hbh, resultCodeOf(answer));
        } catch (TimeoutException e) {
            pending.remove(hbh);
            timedOut.increment();
            throw new DiaLegException("Tw timeout waiting " + cmd.diaName()
                    + " answer (hbh=" + hbh + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pending.remove(hbh);
            throw new DiaLegException("interrupted awaiting " + cmd.diaName(), e);
        } catch (java.util.concurrent.ExecutionException e) {
            pending.remove(hbh);
            throw new DiaLegException("error awaiting " + cmd.diaName()
                    + ": " + e.getCause(), e.getCause());
        }
    }

    DiameterRequest buildRequest(DiaCmd cmd, Map<String, String> avps,
                                  String sessionId) throws DiaLegException {
        java.util.List<AvpTransform> transforms = engine.clientTransformsFor(cmd)
                .orElseThrow(() -> new DiaLegException(
                        cmd + " is not in the MAP→DIA mapping set (HSS-initiated flows land with the MAP leg)"));
        requireMappedSources(cmd, transforms, avps);
        String imsi = avps.get("imsi").trim();
        String plmnStr = avps.getOrDefault("plmn", "45204");
        if (!Plmn.isValid(plmnStr)) {
            throw new DiaLegException("invalid plmn: " + plmnStr);
        }
        byte[] visitedPlmn = Plmn.tbcd(plmnStr);
        RATTypeEnum rat = ratOf(avps.get("rat"));
        try {
            DiameterRequest request = switch (cmd) {
                case ULR -> updateLocationRequest(avps, imsi, visitedPlmn, rat, sessionId);
                case AIR -> authenticationInformationRequest(imsi, visitedPlmn, avps, sessionId);
                case PUR -> purgeRequest(imsi, sessionId);
                case NOR -> notifyRequest(imsi, visitedPlmn, avps, sessionId);
                default -> throw new DiaLegException(
                        cmd + " is not a client-sendable op on this leg");
            };
            tagVendorApp(request, cmd.appId());
            return request;
        } catch (DiaLegException e) {
            throw e;
        } catch (Exception e) {
            throw new DiaLegException("cannot build " + cmd + ": " + e.getMessage(), e);
        }
    }

    /**
     * The engine decides which MAP sources are mandatory for an op; a missing
     * required source fails fast before any byte hits the wire (drop-im-lặng
     * lesson: never send a half-built request).
     */
    private static void requireMappedSources(DiaCmd cmd, java.util.List<AvpTransform> transforms,
                                             Map<String, String> avps) throws DiaLegException {
        for (AvpTransform t : transforms) {
            if (!t.required()) {
                continue;
            }
            String v = avps.get(t.mapSource());
            if (v == null || v.isBlank()) {
                throw new DiaLegException("required mapping source '" + t.mapSource()
                        + "' missing for " + cmd + " (drives " + t.diaAvpName() + ")");
            }
        }
    }

    private UpdateLocationRequest updateLocationRequest(Map<String, String> avps,
                                                        String imsi, byte[] visitedPlmn,
                                                        RATTypeEnum rat,
                                                        String sessionId) throws Exception {
        ULRFlagsImpl flags = new ULRFlagsImpl();
        flags.setS6AS6DIndicationBit(true);
        UpdateLocationRequest req = new UpdateLocationRequestImpl(
                config.originHost(), config.originRealm(),
                config.destHost(), config.destRealm(),
                Boolean.FALSE, sessionId, AuthSessionStateEnum.STATE_MAINTAINED,
                rat, flags, Unpooled.wrappedBuffer(visitedPlmn));
        req.setUsername(imsi);
        String sgsnNumber = avps.get("sgsnNumber");
        if (sgsnNumber != null && !sgsnNumber.isBlank()) {
            req.setSGSNNumber(sgsnNumber);
        }
        return req;
    }

    private AuthenticationInformationRequest authenticationInformationRequest(
            String imsi, byte[] visitedPlmn, Map<String, String> avps,
            String sessionId) throws Exception {
        AuthenticationInformationRequest req = new AuthenticationInformationRequestImpl(
                config.originHost(), config.originRealm(),
                config.destHost(), config.destRealm(),
                Boolean.FALSE, sessionId, AuthSessionStateEnum.STATE_MAINTAINED,
                Unpooled.wrappedBuffer(visitedPlmn));
        req.setUsername(imsi);
        var requestedVectors = new RequestedEUTRANAuthenticationInfoImpl();
        requestedVectors.setNumberOfRequestedVectors(
                Long.parseLong(avps.getOrDefault("vectors", "1")));
        requestedVectors.setImmediateResponsePreferred(1L);
        req.setRequestedEUTRANAuthenticationInfo(requestedVectors);
        return req;
    }

    private PurgeUERequest purgeRequest(String imsi, String sessionId) throws Exception {
        PurgeUERequest req = new PurgeUERequestImpl(
                config.originHost(), config.originRealm(),
                config.destHost(), config.destRealm(),
                Boolean.FALSE, sessionId, AuthSessionStateEnum.STATE_MAINTAINED);
        req.setUsername(imsi);
        return req;
    }

    private NotifyRequest notifyRequest(String imsi, byte[] visitedPlmn,
                                        Map<String, String> avps,
                                        String sessionId) throws Exception {
        NotifyRequest req = new NotifyRequestImpl(
                config.originHost(), config.originRealm(),
                config.destHost(), config.destRealm(),
                Boolean.FALSE, sessionId, AuthSessionStateEnum.STATE_MAINTAINED);
        req.setUsername(imsi);
        req.setVisitedNetworkIdentifier(Unpooled.wrappedBuffer(visitedPlmn));
        return req;
    }

    private void onCorsacIngress(DiameterMessage message, String linkId, AsyncCallback callback) {
        if (message == null) {
            return;
        }
        if (isBaseProtocol(message)) {
            return;
        }
        if (message instanceof DiameterAnswer answer) {
            CompletableFuture<DiameterAnswer> future =
                    pending.remove(answer.getHopByHopIdentifier());
            if (future != null) {
                future.complete(answer);
            } else {
                LOG.debug("[iwf-dia] answer without pending tx hbh={}",
                        answer.getHopByHopIdentifier());
            }
            return;
        }
        if (message instanceof DiameterRequest request) {
            inboundRequests.increment();
            answerUnableToComply(request);
            return;
        }
        LOG.debug("[iwf-dia] ingress ignored class={} link={}", message.getClass(), linkId);
    }

    /**
     * Server-initiated S6a requests (CLR/IDR/DSR/NOR) cannot be forwarded to
     * MAP until the ra-jss7 leg lands (M-IWF-3). Fail loudly and honestly:
     * answer DIAMETER_UNABLE_TO_COMPLY (5012) carrying OUR Origin-Host/Realm
     * (RFC 6733 §6.2 withOrigin law) instead of dropping silently.
     */
    private void answerUnableToComply(DiameterRequest request) {
        int cmdCode = com.mobius.software.telco.protocols.diameter.parser.DiameterParser
                .getCommandDefinition(request.getClass()).commandCode();
        DiaCmd diaCmd = switch (cmdCode) {
            case 317 -> DiaCmd.CLR;
            case 319 -> DiaCmd.IDR;
            case 320 -> DiaCmd.DSR;
            case 323 -> DiaCmd.NOR;
            default -> null;
        };
        try {
            DiameterAnswer answer = diaCmd == null ? null : switch (diaCmd) {
                case CLR -> new CancelLocationAnswerImpl(
                        config.originHost(), config.originRealm(), Boolean.FALSE,
                        ResultCodes.DIAMETER_UNABLE_TO_COMPLY, request.getSessionId(),
                        AuthSessionStateEnum.STATE_MAINTAINED);
                case IDR -> new InsertSubscriberDataAnswerImpl(
                        config.originHost(), config.originRealm(), Boolean.FALSE,
                        ResultCodes.DIAMETER_UNABLE_TO_COMPLY, request.getSessionId(),
                        AuthSessionStateEnum.STATE_MAINTAINED);
                case DSR -> new DeleteSubscriberDataAnswerImpl(
                        config.originHost(), config.originRealm(), Boolean.FALSE,
                        ResultCodes.DIAMETER_UNABLE_TO_COMPLY, request.getSessionId(),
                        AuthSessionStateEnum.STATE_MAINTAINED);
                case NOR -> new NotifyAnswerImpl(
                        config.originHost(), config.originRealm(), Boolean.FALSE,
                        ResultCodes.DIAMETER_UNABLE_TO_COMPLY, request.getSessionId(),
                        AuthSessionStateEnum.STATE_MAINTAINED);
                default -> null;
            };
            if (answer == null) {
                LOG.warn("[iwf-dia] inbound request cmd={} dropped (no answer mapping)", cmdCode);
                return;
            }
            answer.setHopByHopIdentifier(request.getHopByHopIdentifier());
            answer.setEndToEndIdentifier(request.getEndToEndIdentifier());
            tagVendorApp(answer, DiaCmd.S6A_APP_ID);
            DiameterLink link = link();
            if (link == null || !link.isConnected()) {
                LOG.warn("[iwf-dia] cannot answer cmd={} — link down", cmdCode);
                return;
            }
            link.sendMessage(answer, noop());
            inboundAnswered.increment();
            LOG.warn("[iwf-dia] server-initiated cmd={} answered 5012 unable-to-comply "
                    + "(MAP leg lands in M-IWF-3)", cmdCode);
        } catch (Exception e) {
            LOG.warn("[iwf-dia] failed answering inbound cmd={}: {}", cmdCode, e.toString());
        }
    }

    public void sendOnLink(String linkId, Object message) {
        DiameterLink link = link();
        if (link == null || !link.isConnected()) {
            LOG.warn("[iwf-dia] sendOnLink dropped — link down: {}", linkId);
            return;
        }
        if (message instanceof com.mobius.software.telco.protocols.diameter.commands.DiameterMessage dm) {
            link.sendMessage(dm, noop());
            sent.increment();
        } else {
            LOG.warn("[iwf-dia] sendOnLink: unsupported message type {}", message == null ? "null" : message.getClass());
        }
    }

    public void attachEndpoint(IwfRaEndpoint raEndpoint) {
        this.endpoint = raEndpoint;
    }

    public void setBindingRegistry(DialogBindingRegistry registry) {
        this.bindingRegistry = registry;
    }

    public DialogBindingRegistry bindingRegistry() {
        return bindingRegistry;
    }

    private void pollLinks() {
        try {
            mirrorLinkStateOnce();
        } catch (RuntimeException e) {
            LOG.debug("[iwf-dia] link watch error {}", e.toString());
        }
    }

    private void mirrorLinkStateOnce() {
        DiameterStack s = stack;
        DiameterLink link = s == null ? null : s.getNetworkManager().getLink(LINK_ID);
        if (link == null) {
            setOpen(false);
            return;
        }
        boolean connected = link.isConnected();
        boolean open = connected && link.isUp()
                && link.getPeerState() == PeerStateEnum.OPEN;
        setOpen(open);
        if (connected) {
            failAllPendingIfLinkWentDown();
        }
    }

    private void setOpen(boolean open) {
        boolean was = linkOpen;
        linkOpen = open;
        if (was != open) {
            if (open) {
                LOG.info("[iwf-dia] DRA link OPEN (CER/CEA done)");
            } else {
                LOG.warn("[iwf-dia] DRA link down");
                failAllPendingIfLinkWentDown();
            }
        }
    }

    private void failAllPendingIfLinkWentDown() {
        if (!linkOpen) {
            failAllPending("DRA link down");
        }
    }

    private void failAllPending(String reason) {
        pending.forEach((hbh, future) -> {
            if (future.completeExceptionally(new DiaLegException(reason))) {
                LOG.debug("[iwf-dia] pending hbh={} failed: {}", hbh, reason);
            }
        });
    }

    private DiameterLink link() {
        DiameterStack s = stack;
        return s == null ? null : s.getNetworkManager().getLink(LINK_ID);
    }

    private void registerCommandPackages() {
        registerPackagesOn(stack.getGlobalParser(), "global");
    }

    /**
     * corsac decodes inbound frames through the PER-LINK parser (private field
     * of DiameterLinkImpl), which starts empty — without registering command
     * packages there, answers decode to objects whose Result-Code field stays
     * null and stack bookkeeping NPEs. Mirrors DRA CorsacPeerFabric fix.
     */
    private void registerLinkDecodePackages() {
        if (link() instanceof com.mobius.software.telco.protocols.diameter.impl.DiameterLinkImpl impl) {
            try {
                java.lang.reflect.Field pf = com.mobius.software.telco.protocols.diameter.impl.DiameterLinkImpl.class
                        .getDeclaredField("parser");
                pf.setAccessible(true);
                if (pf.get(impl) instanceof com.mobius.software.telco.protocols.diameter.parser.DiameterParser linkParser) {
                    registerPackagesOn(linkParser, "link");
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                LOG.warn("[iwf-dia] cannot access per-link parser: {}", e.toString());
            }
        }
    }

    private void registerPackagesOn(com.mobius.software.telco.protocols.diameter.parser.DiameterParser parser,
                                    String scope) {
        ClassLoader cl = DiameterStackImpl.class.getClassLoader();
        int registered = 0;
        for (String simple : List.of("common", "s6a", "s6c", "s13", "slh", "slg",
                "sh", "rx", "gx", "cx", "swx")) {
            String fq = "com.mobius.software.telco.protocols.diameter.impl.commands." + simple;
            try {
                Package p = materializePackage(cl, fq);
                if (p != null) {
                    parser.registerApplication(cl, p);
                    registered++;
                }
            } catch (Exception e) {
                LOG.debug("[iwf-dia] command package {} on {} skipped: {}", fq, scope, e.toString());
            }
        }
        LOG.info("[iwf-dia] command packages registered {}/11 on {} (all apps)", registered, scope);
    }

    private static Package materializePackage(ClassLoader cl, String fqcn) throws Exception {
        Package existing = Package.getPackage(fqcn);
        if (existing != null) {
            return existing;
        }
        String probe = switch (fqcn.substring(fqcn.lastIndexOf('.') + 1)) {
            case "common" -> ".CapabilitiesExchangeRequestImpl";
            case "s6a" -> ".UpdateLocationRequestImpl";
            case "s6c" -> ".SendRoutingInfoRequestImpl";
            case "s13" -> ".CheckImmediateResponseImpl";
            case "slh" -> ".ProvideSubscriberLocationRequestImpl";
            case "slg" -> ".ProvideSubscriberLocationRequestImpl";
            case "sh" -> ".UserDataRequestImpl";
            case "rx" -> ".AARequestImpl";
            case "gx" -> ".CreditControlRequestImpl";
            case "cx" -> ".UserAuthorizationRequestImpl";
            case "swx" -> ".MultimediaAuthRequestImpl";
            default -> ".CapabilitiesExchangeRequestImpl";
        };
        Class.forName(fqcn + probe, false, cl);
        return Package.getPackage(fqcn);
    }

    private static boolean isBaseProtocol(DiameterMessage message) {
        return message instanceof CapabilitiesExchangeRequest
                || message instanceof CapabilitiesExchangeAnswer
                || message instanceof DeviceWatchdogRequest
                || message instanceof DeviceWatchdogAnswer
                || message instanceof DisconnectPeerRequest
                || message instanceof DisconnectPeerAnswer;
    }

    private static RATTypeEnum ratOf(String value) {
        if (value == null || value.isBlank()) {
            return RATTypeEnum.UTRAN;
        }
        return RATTypeEnum.valueOf(value.trim().toUpperCase()
                .replace('-', '_'));
    }

    private static int resultCodeOf(DiameterAnswer answer) {
        Long rc = answer.getResultCode();
        if (rc != null) {
            return rc.intValue();
        }
        try {
            if (answer.getExperimentalResult() != null
                    && answer.getExperimentalResult().getExperimentalResultCode() != null) {
                return answer.getExperimentalResult().getExperimentalResultCode().intValue();
            }
        } catch (DiameterException ignored) {
            // experimental result AVP malformed — fall through to unknown
        }
        return -1;
    }

    /**
     * corsac canSendMessage() requires the message to carry a
     * Vendor-Specific-Application-Id AVP matching the peer capability —
     * without it the send is rejected DIAMETER_APPLICATION_UNSUPPORTED
     * (family lesson: testapp S6aHandler.tagVendorAppId). Always tag.
     */
    private static void tagVendorApp(DiameterMessage message, long appId) {
        try {
            var vsa = new com.mobius.software.telco.protocols.diameter.impl.primitives.common.VendorSpecificApplicationIdImpl();
            vsa.setAuthApplicationId(appId);
            if (message instanceof com.mobius.software.telco.protocols.diameter.commands.commons.VendorSpecificRequest req) {
                req.setVendorSpecificApplicationId(vsa);
            } else if (message instanceof com.mobius.software.telco.protocols.diameter.commands.commons.VendorSpecificAnswer ans) {
                ans.setVendorSpecificApplicationId(vsa);
            }
        } catch (Exception e) {
            LOG.warn("[iwf-dia] cannot tag Vendor-Specific-Application-Id: {}", e.toString());
        }
    }

    private AsyncCallback noop() {
        return new AsyncCallback() {
            @Override
            public void onSuccess() {
            }

            @Override
            public void onError(DiameterException e) {
                LOG.warn("[iwf-dia] async send rejected: {}", e.getMessage());
            }
        };
    }

    private void teardownQuietly() {
        ScheduledExecutorService watcher = linkWatcher;
        linkWatcher = null;
        if (watcher != null) {
            watcher.shutdownNow();
        }
        DiameterStack s = stack;
        stack = null;
        if (s != null) {
            try {
                s.stop();
            } catch (RuntimeException e) {
                LOG.warn("[iwf-dia] stack stop error", e);
            }
        }
        WorkerPool wp = workerPool;
        workerPool = null;
        if (wp != null) {
            try {
                wp.stop();
            } catch (RuntimeException e) {
                LOG.warn("[iwf-dia] worker pool stop error", e);
            }
        }
        linkOpen = false;
    }

    public Map<String, Object> healthSnapshot() {
        DiameterLink link = link();
        return Map.of(
                "target", config.draHost() + ":" + config.draPort(),
                "srcPort", config.srcPort(),
                "originHost", config.originHost(),
                "open", ready(),
                "transportUp", link != null && link.isConnected(),
                "sent", sent.sum(),
                "answered", answered.sum(),
                "timedOut", timedOut.sum(),
                "inboundRequests", inboundRequests.sum(),
                "inboundAnswered5012", inboundAnswered.sum());
    }
}
