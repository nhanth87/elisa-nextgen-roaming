package et.elisa.iwf.map;

import et.elisa.iwf.diameter.DiaApp;

/**
 * MAP operations from 3GPP TS 29.002, covering the full roaming
 * interworking set. Each constant carries its op code, ASN name, and
 * the {@link DiaApp} it interworks with (null = MAP-only, no Diameter
 * counterpart defined in TS 29.305).
 *
 * <p>Grouped by interworking domain:
 * <ul>
 *   <li><b>Mobility (S6a/S6d):</b> UPDATE_LOCATION, UPDATE_GPRS_LOCATION,
 *       SEND_AUTHENTICATION_INFO, PURGE_MS, INSERT/DELETE/CANCEL location,
 *       NOTIFY_GPRS, RESTORE_DATA, NOTE_SUBSCRIBER_DATA_CHANGE</li>
 *   <li><b>CS Voice (S6c):</b> SEND_ROUTING_INFO_FOR_CS, PROVIDE_ROAMING_NUMBER</li>
 *   <li><b>SMS (S6c):</b> SEND_INFO_FOR_{IN,OUT}GOING_SM, REPORT_SM_DELIVERY_STATUS,
 *       READY_FOR_SM</li>
 *   <li><b>CAMEL (S13):</b> PROVIDE_SUBSCRIBER_INFO, DEBUT_TRACE,
 *       ADD_CAMEL_SUBSCRIPTION_INFO</li>
 *   <li><b>LCS (SLg):</b> PROVIDE_SUBSCRIBER_LOCATION, SUBSCRIBER_LOCATION_REPORT</li>
 *   <li><b>SS/USSD:</b> REGISTER/ACTIVATE/DEACTIVATE/INTERROGATE/ERASE_SS,
 *       UNSTRUCTURED_SS_REQUEST</li>
 *   <li><b>Handover:</b> PREPARE_HO, PREPARE_SUBSEQUENT_HO, ALLOCATE_HO_NUMBER,
 *       SEND_END_SIGNAL, PROCESS/FORWARD_ACCESS_SIGNALLING</li>
 *   <li><b>Other:</b> PROCESS_ACCESS_REQUEST, NOTE_INTERACTION, FORWARD_ACCESS_SIGNALLING</li>
 * </ul>
 *
 * <p>Op codes pinned to TS 29.002 v19 (Rel-19).
 */
public enum MapOp {

    // ── Mobility management (S6a/S6d) ───────────────────────────────
    PROCESS_ACCESS_REQUEST  (1,   "processAccessRequest",               DiaApp.S6A),
    UPDATE_LOCATION         (2,   "updateLocation",                     DiaApp.S6A),
    CANCEL_LOCATION         (3,   "cancelLocation",                     DiaApp.S6A),
    SEND_AUTHENTICATION_INFO(56,  "sendAuthenticationInfo",             DiaApp.S6A),
    INSERT_SUBSCRIBER_DATA  (7,   "insertSubscriberData",               DiaApp.S6A),
    DELETE_SUBSCRIBER_DATA  (8,   "deleteSubscriberData",               DiaApp.S6A),
    PURGE_MS                (51,  "purgeMS",                            DiaApp.S6A),
    UPDATE_GPRS_LOCATION    (48,  "updateGprsLocation",                 DiaApp.S6A),
    NOTIFY_GPRS             (44,  "notifyGPRS",                         DiaApp.S6A),
    RESTORE_DATA            (20,  "restoreData",                        DiaApp.S6A),
    NOTE_SUBSCRIBER_DATA_CHANGE(76, "noteSubscriberDataChange",         DiaApp.S6A),
    READY_FOR_SM            (60,  "readyForSM",                         DiaApp.S6C),

    // ── CS Voice interworking (S6c) ─────────────────────────────────
    SEND_ROUTING_INFO_FOR_CS(5,  "sendRoutingInfoForCS",                DiaApp.S6C),
    PROVIDE_ROAMING_NUMBER  (22,  "provideRoamingNumber",               DiaApp.S6C),

    // ── SMS interworking (S6c) ──────────────────────────────────────
    SEND_INFO_FOR_OUTGOING_SM(47, "sendInfoForOutgoingSM",              DiaApp.S6C),
    SEND_INFO_FOR_INCOMING_SM(46, "sendInfoForIncomingSM",              DiaApp.S6C),
    REPORT_SM_DELIVERY_STATUS(43, "reportSMDeliveryStatus",             DiaApp.S6C),

    // ── CAMEL / tracing (S13) ───────────────────────────────────────
    PROVIDE_SUBSCRIBER_INFO(69,  "provideSubscriberInfo",               DiaApp.S13),
    DEBUT_TRACE            (34,  "debutTrace",                          DiaApp.S13),
    ADD_CAMEL_SUBSCRIPTION_INFO(77, "addCamelSubscriptionInfo",         DiaApp.S13),

    // ── LCS (SLg) ───────────────────────────────────────────────────
    PROVIDE_SUBSCRIBER_LOCATION(23, "provideSubscriberLocation",       DiaApp.SLG),
    SUBSCRIBER_LOCATION_REPORT(41,  "subscriberLocationReport",        DiaApp.SLG),

    // ── SS (MAP-only — no Diameter pair in TS 29.305) ───────────────
    REGISTER_SS            (50,  "registerSS",                          null),
    DEACTIVATE_SS          (52,  "deactivateSS",                        null),
    ACTIVATE_SS            (53,  "activateSS",                          null),
    INTERROGATE_SS         (54,  "interrogateSS",                       null),
    ERASE_SS               (55,  "eraseSS",                             null),

    // ── USSD (MAP-only) ─────────────────────────────────────────────
    UNSTRUCTURED_SS_REQUEST(70,  "unstructuredSSRequest",               null),

    // ── Handover (MAP-only) ─────────────────────────────────────────
    PREPARE_HO             (61,  "prepareHandover",                     null),
    PREPARE_SUBSEQUENT_HO  (62,  "prepareSubsequentHandover",           null),
    ALLOCATE_HANDOVER_NUMBER(63, "allocateHandoverNumber",              null),
    SEND_END_SIGNAL        (64,  "sendEndSignal",                       null),
    PROCESS_ACCESS_SIGNALLING(65, "processAccessSignalling",            null),
    FORWARD_ACCESS_SIGNALLING(66, "forwardAccessSignalling",            null),

    // ── Other ───────────────────────────────────────────────────────
    NOTE_INTERACTION       (33,  "noteInteraction",                     null);

    private final int opCode;
    private final String asnName;
    private final DiaApp diaApp;

    MapOp(int opCode, String asnName, DiaApp diaApp) {
        this.opCode = opCode;
        this.asnName = asnName;
        this.diaApp = diaApp;
    }

    public int opCode() {
        return opCode;
    }

    public String asnName() {
        return asnName;
    }

    /**
     * The Diameter application this MAP operation interworks with, or
     * {@code null} if it has no Diameter counterpart in TS 29.305.
     */
    public DiaApp diaApp() {
        return diaApp;
    }
}
