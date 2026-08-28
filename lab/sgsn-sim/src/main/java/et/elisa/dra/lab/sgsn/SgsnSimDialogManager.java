package et.elisa.dra.lab.sgsn;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.restcomm.protocols.ss7.map.api.MAPApplicationContext;
import org.restcomm.protocols.ss7.map.api.MAPApplicationContextName;
import org.restcomm.protocols.ss7.map.api.MAPApplicationContextVersion;
import org.restcomm.protocols.ss7.map.api.MAPDialog;
import org.restcomm.protocols.ss7.map.api.MAPException;
import org.restcomm.protocols.ss7.map.api.MAPMessage;
import org.restcomm.protocols.ss7.map.api.dialog.Reason;
import org.restcomm.protocols.ss7.map.api.errors.MAPErrorMessage;
import org.restcomm.protocols.ss7.map.api.primitives.AddressNature;
import org.restcomm.protocols.ss7.map.api.primitives.AddressString;
import org.restcomm.protocols.ss7.map.api.primitives.IMSI;
import org.restcomm.protocols.ss7.map.api.primitives.ISDNAddressString;
import org.restcomm.protocols.ss7.map.api.primitives.NumberingPlan;
import org.restcomm.protocols.ss7.map.api.service.mobility.MAPDialogMobility;
import org.restcomm.protocols.ss7.map.api.service.mobility.MAPServiceMobility;
import org.restcomm.protocols.ss7.map.api.service.mobility.MAPServiceMobilityListener;
import org.restcomm.protocols.ss7.map.api.service.mobility.authentication.AuthenticationFailureReportRequest;
import org.restcomm.protocols.ss7.map.api.service.mobility.authentication.AuthenticationFailureReportResponse;
import org.restcomm.protocols.ss7.map.api.service.mobility.authentication.SendAuthenticationInfoRequest;
import org.restcomm.protocols.ss7.map.api.service.mobility.authentication.SendAuthenticationInfoResponse;
import org.restcomm.protocols.ss7.map.api.service.mobility.faultRecovery.ForwardCheckSSIndicationRequest;
import org.restcomm.protocols.ss7.map.api.service.mobility.faultRecovery.ResetRequest;
import org.restcomm.protocols.ss7.map.api.service.mobility.faultRecovery.RestoreDataRequest;
import org.restcomm.protocols.ss7.map.api.service.mobility.faultRecovery.RestoreDataResponse;
import org.restcomm.protocols.ss7.map.api.service.mobility.imei.CheckImeiRequest;
import org.restcomm.protocols.ss7.map.api.service.mobility.imei.CheckImeiResponse;
import org.restcomm.protocols.ss7.map.api.service.mobility.oam.ActivateTraceModeRequest_Mobility;
import org.restcomm.protocols.ss7.map.api.service.mobility.oam.ActivateTraceModeResponse_Mobility;
import org.restcomm.protocols.ss7.map.api.service.mobility.locationManagement.CancelLocationRequest;
import org.restcomm.protocols.ss7.map.api.service.mobility.locationManagement.CancelLocationResponse;
import org.restcomm.protocols.ss7.map.api.service.mobility.locationManagement.PurgeMSRequest;
import org.restcomm.protocols.ss7.map.api.service.mobility.locationManagement.PurgeMSResponse;
import org.restcomm.protocols.ss7.map.api.service.mobility.locationManagement.SendIdentificationRequest;
import org.restcomm.protocols.ss7.map.api.service.mobility.locationManagement.SendIdentificationResponse;
import org.restcomm.protocols.ss7.map.api.service.mobility.locationManagement.UpdateGprsLocationRequest;
import org.restcomm.protocols.ss7.map.api.service.mobility.locationManagement.UpdateGprsLocationResponse;
import org.restcomm.protocols.ss7.map.api.service.mobility.locationManagement.UpdateLocationRequest;
import org.restcomm.protocols.ss7.map.api.service.mobility.locationManagement.UpdateLocationResponse;
import org.restcomm.protocols.ss7.map.api.service.mobility.subscriberInformation.AnyTimeInterrogationRequest;
import org.restcomm.protocols.ss7.map.api.service.mobility.subscriberInformation.AnyTimeInterrogationResponse;
import org.restcomm.protocols.ss7.map.api.service.mobility.subscriberInformation.AnyTimeModificationRequest;
import org.restcomm.protocols.ss7.map.api.service.mobility.subscriberInformation.AnyTimeModificationResponse;
import org.restcomm.protocols.ss7.map.api.service.mobility.subscriberInformation.AnyTimeSubscriptionInterrogationRequest;
import org.restcomm.protocols.ss7.map.api.service.mobility.subscriberInformation.AnyTimeSubscriptionInterrogationResponse;
import org.restcomm.protocols.ss7.map.api.service.mobility.subscriberInformation.ProvideSubscriberInfoRequest;
import org.restcomm.protocols.ss7.map.api.service.mobility.subscriberInformation.ProvideSubscriberInfoResponse;
import org.restcomm.protocols.ss7.map.api.service.mobility.subscriberManagement.DeleteSubscriberDataRequest;
import org.restcomm.protocols.ss7.map.api.service.mobility.subscriberManagement.DeleteSubscriberDataResponse;
import org.restcomm.protocols.ss7.map.api.service.mobility.subscriberManagement.InsertSubscriberDataRequest;
import org.restcomm.protocols.ss7.map.api.service.mobility.subscriberManagement.InsertSubscriberDataResponse;
import org.restcomm.protocols.ss7.tcap.asn.comp.Problem;

import et.elisa.iwf.map.MapOp;


/**
 * High-level MAP dialog driver for the lab SGSN-sim (L3 oracle side of
 * {@code docs/plans/iwf-e2e-test-plan.md}).
 *
 * <p>Outbound: SGSN-initiated MAP BEGIN toward the IWF (UpdateGprsLocation /
 * SendAuthenticationInfo / PurgeMS / NotifyGPRS). Inbound: HSS-initiated
 * Cancel/Insert/Delete are answered with a generic success to test the
 * DIA→MAP leg of the bridge. All other mobility ops are refused so the
 * dialog closes cleanly. Runs on the jSS7 MAP delivery thread — heavy work
 * is deferred to a virtual thread (AGENTS: no blocking IO on the event frame).
 */
public final class SgsnSimDialogManager implements MAPServiceMobilityListener {

    private static final Logger LOG = LogManager.getLogger(SgsnSimDialogManager.class);

    private final SgsnSimStack stack;
    private final DialogLogWriter logWriter;
    private final Map<Long, CompletableFuture<MapResult>> pendingByLocalDialog = new ConcurrentHashMap<>();
    private final Map<MapOp, Long> okCounters = new ConcurrentHashMap<>();
    private final Map<MapOp, Long> errorCounters = new ConcurrentHashMap<>();

    public SgsnSimDialogManager(SgsnSimStack stack, DialogLogWriter logWriter) throws MAPException {
        this.stack = stack;
        this.logWriter = logWriter;
        stack.mapProvider().getMAPServiceMobility().addMAPServiceListener(this);
    }

    public Map<MapOp, Long> okCounters() {
        return new java.util.HashMap<>(okCounters);
    }

    public Map<MapOp, Long> errorCounters() {
        return new java.util.HashMap<>(errorCounters);
    }

    // ── Outbound MAP operations (SGSN → IWF via STP) ────────────────

    public CompletableFuture<MapResult> sendUpdateGprsLocation(String imsi, String sgsnNumber) {
        return send(MapOp.UPDATE_GPRS_LOCATION, MAPApplicationContextName.gprsLocationUpdateContext,
                MAPApplicationContextVersion.version3, dialog -> {
            IMSI imsiP = stack.mapParameterFactory().createIMSI(imsi);
            ISDNAddressString ggsn = stack.mapParameterFactory().createISDNAddressString(
                    null, NumberingPlan.land_mobile, sgsnNumber);
            return dialog.addUpdateGprsLocationRequest(
                    dialog.getLocalDialogId().intValue(),   // 1 invoke
                    imsiP, ggsn,                           // 2 IMSI, 3 ISDN
                    null, null, null,                      // 4 GSN, 5 Ext, 6 SGSNCap
                    false, false,                          // 7,8 bool
                    null, null, null,                      // 9 GSN,10 ADDInfo,11 EPS
                    false, false,                          // 12,13 bool
                    null,                                  // 14 UsedRATType
                    false, false, false, false, false,     // 15..19 bool
                    null,                                  // 20 UE-SRVCC-cap
                    null,                                  // 21 List<PlmnId>
                    null,                                  // 22 ISDNAddressString
                    null,                                  // 23 SMSRegisterReq
                    false,                                 // 24 bool
                    null, null,                            // 25,26 DiameterIdentity
                    false, false, null);                   // 27,28 bool, 29 List<PlmnId>
                });
    }

    public CompletableFuture<MapResult> sendSendAuthenticationInfo(String imsi) {
        return send(MapOp.SEND_AUTHENTICATION_INFO, MAPApplicationContextName.infoRetrievalContext,
                MAPApplicationContextVersion.version3, dialog ->
                dialog.addSendAuthenticationInfoRequest(dialog.getLocalDialogId().intValue(),
                        stack.mapParameterFactory().createIMSI(imsi),
                        5, false, false, null, null, null, null, null, false, true));
    }

    public CompletableFuture<MapResult> sendPurgeMS(String imsi, String sgsnNumber) {
        return send(MapOp.PURGE_MS, MAPApplicationContextName.msPurgingContext,
                MAPApplicationContextVersion.version3, dialog -> {
                    ISDNAddressString sgsn = stack.mapParameterFactory().createISDNAddressString(
                            null, NumberingPlan.land_mobile, sgsnNumber);
                    return dialog.addPurgeMSRequest(dialog.getLocalDialogId().intValue(),
                            stack.mapParameterFactory().createIMSI(imsi), sgsn,
                            null, null, null, null, null);
                });
    }

    public CompletableFuture<MapResult> sendNotifyGPRS(String imsi, String sgsmNumber) {
        return send(MapOp.NOTIFY_GPRS, MAPApplicationContextName.mmEventReportingContext,
                MAPApplicationContextVersion.version3, dialog -> {
                    ISDNAddressString sgsn = stack.mapParameterFactory().createISDNAddressString(
                            null, NumberingPlan.land_mobile, sgsmNumber);
                    return dialog.addUpdateGprsLocationRequest(dialog.getLocalDialogId().intValue(),
                            stack.mapParameterFactory().createIMSI(imsi), sgsn,
                            null, null, null, false, false, null, null, null,
                            false, false, null, false, false, false, false, false,
                            null, null, null, null, false, null, null, false, false, null);
                });
    }

    private CompletableFuture<MapResult> send(MapOp op, MAPApplicationContextName ctxName,
                                              MAPApplicationContextVersion ctxVer, RequestBlock request) {
        CompletableFuture<MapResult> future = new CompletableFuture<>();
        try {
            MAPApplicationContext ctx = MAPApplicationContext.getInstance(ctxName, ctxVer);
            AddressString origRef = stack.mapParameterFactory().createAddressString(
                    AddressNature.international_number, NumberingPlan.land_mobile, "999");
            MAPDialogMobility dialog = stack.mapProvider().getMAPServiceMobility()
                    .createNewDialog(ctx, stack.localSccpAddress(), origRef, stack.iwfSccpAddress(), null);
            request.invoke(dialog);
            pendingByLocalDialog.put(dialog.getLocalDialogId(), future);
            dialog.send();
            LOG.info("[sgsn] Tx MAP BEGIN op={} localDlg={}", op, dialog.getLocalDialogId());
        } catch (MAPException e) {
            future.completeExceptionally(e);
        }
        return future.whenComplete((r, ex) -> {
            if (ex != null || r == null || !r.success()) errorCounters.merge(op, 1L, Long::sum);
            else okCounters.merge(op, 1L, Long::sum);
        });
    }

    @FunctionalInterface
    private interface RequestBlock {
        Long invoke(MAPDialogMobility dialog) throws MAPException;
    }


    // ── Inbound: HSS-initiated (via IWF) → answer generic success ──

    @Override
    public void onCancelLocationRequest(CancelLocationRequest ind) {
        try {
            ind.getMAPDialog().addCancelLocationResponse(ind.getInvokeId(), null);
            logOk(MapOp.CANCEL_LOCATION, ind.getMAPDialog().getLocalDialogId());
        } catch (MAPException e) {
            LOG.warn("[sgsn] answerCancel failed", e);
        }
    }

    @Override
    public void onInsertSubscriberDataRequest(InsertSubscriberDataRequest ind) {
        try {
            ind.getMAPDialog().addInsertSubscriberDataResponse(ind.getInvokeId(),
                    new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), null, null);
            logOk(MapOp.INSERT_SUBSCRIBER_DATA, ind.getMAPDialog().getLocalDialogId());
        } catch (MAPException e) {
            LOG.warn("[sgsn] answerInsert failed", e);
        }
    }

    @Override
    public void onDeleteSubscriberDataRequest(DeleteSubscriberDataRequest ind) {
        try {
            ind.getMAPDialog().addDeleteSubscriberDataResponse(ind.getInvokeId(), null, null);
            logOk(MapOp.DELETE_SUBSCRIBER_DATA, ind.getMAPDialog().getLocalDialogId());
        } catch (MAPException e) {
            LOG.warn("[sgsn] answerDelete failed", e);
        }
    }

    @Override
    public void onUpdateLocationRequest(UpdateLocationRequest ind) {
        try {
            ind.getMAPDialog().addUpdateLocationResponse(ind.getInvokeId(), null, null, true, true);
            logOk(MapOp.UPDATE_LOCATION, ind.getMAPDialog().getLocalDialogId());
        } catch (MAPException e) {
            LOG.warn("[sgsn] answerUpdateLoc failed", e);
        }
    }

    // ── Outbound replies (MPL→SGSN) → complete the pending future ──

    @Override
    public void onCancelLocationResponse(CancelLocationResponse ind) {
        complete(MapOp.CANCEL_LOCATION, ind.getMAPDialog());
    }

    @Override
    public void onInsertSubscriberDataResponse(InsertSubscriberDataResponse ind) {
        complete(MapOp.INSERT_SUBSCRIBER_DATA, ind.getMAPDialog());
    }

    @Override
    public void onDeleteSubscriberDataResponse(DeleteSubscriberDataResponse ind) {
        complete(MapOp.DELETE_SUBSCRIBER_DATA, ind.getMAPDialog());
    }

    @Override
    public void onUpdateLocationResponse(UpdateLocationResponse ind) {
        complete(MapOp.UPDATE_LOCATION, ind.getMAPDialog());
    }

    @Override
    public void onUpdateGprsLocationResponse(UpdateGprsLocationResponse ind) {
        complete(MapOp.UPDATE_GPRS_LOCATION, ind.getMAPDialog());
    }

    @Override
    public void onSendAuthenticationInfoResponse(SendAuthenticationInfoResponse ind) {
        complete(MapOp.SEND_AUTHENTICATION_INFO, ind.getMAPDialog());
    }

    @Override
    public void onPurgeMSResponse(PurgeMSResponse ind) {
        complete(MapOp.PURGE_MS, ind.getMAPDialog());
    }

    @Override
    public void onRestoreDataResponse(RestoreDataResponse ind) {
        complete(MapOp.RESTORE_DATA, ind.getMAPDialog());
    }

    // ── MAPServiceListener (dialog-level) callbacks ──────────────────

    @Override
    public void onErrorComponent(MAPDialog dialog, Long invokeId, MAPErrorMessage error) {
        LOG.debug("[sgsn] error component dialog={} invoke={} err={}",
                dialog == null ? null : dialog.getLocalDialogId(), invokeId, error);
    }

    @Override
    public void onRejectComponent(MAPDialog dialog, Long invokeId,
                                  org.restcomm.protocols.ss7.tcap.asn.comp.Problem problem, boolean b) {
        LOG.debug("[sgsn] reject component dialog={} invoke={} problem={}",
                dialog == null ? null : dialog.getLocalDialogId(), invokeId, problem);
    }

    @Override
    public void onInvokeTimeout(MAPDialog dialog, Long invokeId) {
        LOG.debug("[sgsn] invoke timeout dialog={} invoke={}",
                dialog == null ? null : dialog.getLocalDialogId(), invokeId);
    }

    @Override
    public void onMAPMessage(MAPMessage message) {
        LOG.debug("[sgsn] MAP message {}", message == null ? null : message.getMAPDialog());
    }

    // ── helpers ──────────────────────────────────────────────────────

    private void complete(MapOp op, MAPDialog dialog) {
        if (dialog == null) {
            return;
        }
        CompletableFuture<MapResult> future = pendingByLocalDialog.remove(dialog.getLocalDialogId());
        if (future != null) {
            future.complete(new MapResult(op, 2001, true, null, dialog.getLocalDialogId()));
        }
    }

    private void logOk(MapOp op, Long dialogId) {
        okCounters.merge(op, 1L, Long::sum);
        LOG.info("[sgsn] answered op={} dlg={}", op, dialogId);
    }

    public record MapResult(MapOp op, int resultCode, boolean success, String error, Long localDialogId) { }
    public record MapOpStats(Map<MapOp, Long> ok, Map<MapOp, Long> err) { }


    @Override
    public void onSendIdentificationRequest(org.restcomm.protocols.ss7.map.api.service.mobility.locationManagement.SendIdentificationRequest ind) {
        LOG.debug("[sgsn] ignored mobility op onSendIdentificationRequest ind={}", ind);
    }

    @Override
    public void onSendIdentificationResponse(org.restcomm.protocols.ss7.map.api.service.mobility.locationManagement.SendIdentificationResponse ind) {
        LOG.debug("[sgsn] ignored mobility op onSendIdentificationResponse ind={}", ind);
    }

    @Override
    public void onUpdateGprsLocationRequest(org.restcomm.protocols.ss7.map.api.service.mobility.locationManagement.UpdateGprsLocationRequest ind) {
        LOG.debug("[sgsn] ignored mobility op onUpdateGprsLocationRequest ind={}", ind);
    }

    @Override
    public void onPurgeMSRequest(org.restcomm.protocols.ss7.map.api.service.mobility.locationManagement.PurgeMSRequest ind) {
        LOG.debug("[sgsn] ignored mobility op onPurgeMSRequest ind={}", ind);
    }

    @Override
    public void onSendAuthenticationInfoRequest(org.restcomm.protocols.ss7.map.api.service.mobility.authentication.SendAuthenticationInfoRequest ind) {
        LOG.debug("[sgsn] ignored mobility op onSendAuthenticationInfoRequest ind={}", ind);
    }

    @Override
    public void onAuthenticationFailureReportRequest(org.restcomm.protocols.ss7.map.api.service.mobility.authentication.AuthenticationFailureReportRequest ind) {
        LOG.debug("[sgsn] ignored mobility op onAuthenticationFailureReportRequest ind={}", ind);
    }

    @Override
    public void onAuthenticationFailureReportResponse(org.restcomm.protocols.ss7.map.api.service.mobility.authentication.AuthenticationFailureReportResponse ind) {
        LOG.debug("[sgsn] ignored mobility op onAuthenticationFailureReportResponse ind={}", ind);
    }

    @Override
    public void onResetRequest(org.restcomm.protocols.ss7.map.api.service.mobility.faultRecovery.ResetRequest ind) {
        LOG.debug("[sgsn] ignored mobility op onResetRequest ind={}", ind);
    }

    @Override
    public void onForwardCheckSSIndicationRequest(org.restcomm.protocols.ss7.map.api.service.mobility.faultRecovery.ForwardCheckSSIndicationRequest ind) {
        LOG.debug("[sgsn] ignored mobility op onForwardCheckSSIndicationRequest ind={}", ind);
    }

    @Override
    public void onRestoreDataRequest(org.restcomm.protocols.ss7.map.api.service.mobility.faultRecovery.RestoreDataRequest ind) {
        LOG.debug("[sgsn] ignored mobility op onRestoreDataRequest ind={}", ind);
    }

    @Override
    public void onAnyTimeInterrogationRequest(org.restcomm.protocols.ss7.map.api.service.mobility.subscriberInformation.AnyTimeInterrogationRequest ind) {
        LOG.debug("[sgsn] ignored mobility op onAnyTimeInterrogationRequest ind={}", ind);
    }

    @Override
    public void onAnyTimeInterrogationResponse(org.restcomm.protocols.ss7.map.api.service.mobility.subscriberInformation.AnyTimeInterrogationResponse ind) {
        LOG.debug("[sgsn] ignored mobility op onAnyTimeInterrogationResponse ind={}", ind);
    }

    @Override
    public void onAnyTimeSubscriptionInterrogationRequest(org.restcomm.protocols.ss7.map.api.service.mobility.subscriberInformation.AnyTimeSubscriptionInterrogationRequest ind) {
        LOG.debug("[sgsn] ignored mobility op onAnyTimeSubscriptionInterrogationRequest ind={}", ind);
    }

    @Override
    public void onAnyTimeSubscriptionInterrogationResponse(org.restcomm.protocols.ss7.map.api.service.mobility.subscriberInformation.AnyTimeSubscriptionInterrogationResponse ind) {
        LOG.debug("[sgsn] ignored mobility op onAnyTimeSubscriptionInterrogationResponse ind={}", ind);
    }

    @Override
    public void onAnyTimeModificationRequest(org.restcomm.protocols.ss7.map.api.service.mobility.subscriberInformation.AnyTimeModificationRequest ind) {
        LOG.debug("[sgsn] ignored mobility op onAnyTimeModificationRequest ind={}", ind);
    }

    @Override
    public void onAnyTimeModificationResponse(org.restcomm.protocols.ss7.map.api.service.mobility.subscriberInformation.AnyTimeModificationResponse ind) {
        LOG.debug("[sgsn] ignored mobility op onAnyTimeModificationResponse ind={}", ind);
    }

    @Override
    public void onProvideSubscriberInfoRequest(org.restcomm.protocols.ss7.map.api.service.mobility.subscriberInformation.ProvideSubscriberInfoRequest ind) {
        LOG.debug("[sgsn] ignored mobility op onProvideSubscriberInfoRequest ind={}", ind);
    }

    @Override
    public void onProvideSubscriberInfoResponse(org.restcomm.protocols.ss7.map.api.service.mobility.subscriberInformation.ProvideSubscriberInfoResponse ind) {
        LOG.debug("[sgsn] ignored mobility op onProvideSubscriberInfoResponse ind={}", ind);
    }

    @Override
    public void onCheckImeiRequest(org.restcomm.protocols.ss7.map.api.service.mobility.imei.CheckImeiRequest ind) {
        LOG.debug("[sgsn] ignored mobility op onCheckImeiRequest ind={}", ind);
    }

    @Override
    public void onCheckImeiResponse(org.restcomm.protocols.ss7.map.api.service.mobility.imei.CheckImeiResponse ind) {
        LOG.debug("[sgsn] ignored mobility op onCheckImeiResponse ind={}", ind);
    }

    @Override
    public void onActivateTraceModeRequest_Mobility(org.restcomm.protocols.ss7.map.api.service.mobility.oam.ActivateTraceModeRequest_Mobility ind) {
        LOG.debug("[sgsn] ignored mobility op onActivateTraceModeRequest_Mobility ind={}", ind);
    }

    @Override
    public void onActivateTraceModeResponse_Mobility(org.restcomm.protocols.ss7.map.api.service.mobility.oam.ActivateTraceModeResponse_Mobility ind) {
        LOG.debug("[sgsn] ignored mobility op onActivateTraceModeResponse_Mobility ind={}", ind);
    }
}
