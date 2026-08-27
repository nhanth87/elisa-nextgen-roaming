package et.elisa.stp.gtt;

import org.restcomm.protocols.ss7.sccp.parameter.SccpAddress;

/**
 * Outcome of one {@link GttHarness#resolve} lookup.
 *
 * <p>{@link Status#RESOLVED} carries the hidden DPC+SSN the called GT maps to
 * (DESIGN §3: internal PCs hidden behind GT ranges) plus the translated CdPA
 * exactly as jSS7 {@code RuleImpl.translate} produced it. {@link Status#UNRESOLVED}
 * means no rule matched — the stack would answer
 * {@code NO_TRANSLATION_FOR_ADDRESS} (see
 * {@code Ss7ExtSccpDetailedImpl.translationFunction}).</p>
 *
 * @param status                RESOLVED or UNRESOLVED
 * @param ruleId                matched rule id ({@code 0} when unresolved)
 * @param service               matched rule's service cluster ({@code null} when unresolved)
 * @param dpc                   hidden destination point code ({@code -1} when unresolved)
 * @param ssn                   hidden subsystem number ({@code -1} when unresolved)
 * @param translatedCalledParty the rewritten CdPA ({@code null} when unresolved);
 *                              the CgPA is never part of a translation result —
 *                              GTT rewrites the CdPA only (DESIGN §9.4.2)
 */
public record GttResult(
        Status status,
        int ruleId,
        String service,
        int dpc,
        int ssn,
        SccpAddress translatedCalledParty) {

    public enum Status { RESOLVED, UNRESOLVED }

    public GttResult {
        if (status == Status.RESOLVED) {
            if (ruleId < 1 || service == null || dpc < 1 || ssn < 1 || translatedCalledParty == null) {
                throw new IllegalArgumentException("GttResult RESOLVED requires ruleId/service/dpc/ssn/translatedCalledParty");
            }
        } else {
            if (ruleId != 0 || service != null || dpc != -1 || ssn != -1 || translatedCalledParty != null) {
                throw new IllegalArgumentException("GttResult UNRESOLVED must carry no routing data");
            }
        }
    }

    static GttResult resolved(int ruleId, String service, SccpAddress translatedCalledParty) {
        return new GttResult(Status.RESOLVED, ruleId, service,
                translatedCalledParty.getSignalingPointCode(),
                translatedCalledParty.getSubsystemNumber(),
                translatedCalledParty);
    }

    static GttResult unresolved() {
        return new GttResult(Status.UNRESOLVED, 0, null, -1, -1, null);
    }

    public boolean resolved() {
        return status == Status.RESOLVED;
    }
}