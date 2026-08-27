package et.elisa.stp.gtt;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.restcomm.protocols.ss7.indicator.NatureOfAddress;
import org.restcomm.protocols.ss7.indicator.RoutingIndicator;
import org.restcomm.protocols.ss7.sccp.LoadSharingAlgorithm;
import org.restcomm.protocols.ss7.sccp.OriginationType;
import org.restcomm.protocols.ss7.sccp.RuleType;
import org.restcomm.protocols.ss7.sccp.impl.parameter.GlobalTitle0001Impl;
import org.restcomm.protocols.ss7.sccp.impl.parameter.GlobalTitle0010Impl;
import org.restcomm.protocols.ss7.sccp.impl.parameter.SccpAddressImpl;
import org.restcomm.protocols.ss7.sccp.parameter.SccpAddress;
import org.restcomm.protocols.ss7.sccpext.impl.router.RuleImpl;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * GTT rule test harness (DESIGN §10.2 P5+P7 adaptation): loads the per-service
 * GT fixture JSON, builds the jSS7 SCCP {@link RuleImpl} objects the same way
 * {@code RouterExtImpl.addRule} does, and resolves a called-party GT to the
 * hidden DPC+SSN — or {@link GttResult.Status#UNRESOLVED}.
 *
 * <p><b>Scope.</b> Pure, unit-testable component. It reproduces the two halves
 * of {@code Ss7ExtSccpDetailedImpl.translationFunction} that depend only on the
 * rule table: rule lookup ({@code RouterExtImpl.findRule}) and the CdPA rewrite
 * ({@code RuleImpl.translate}). Stack-level concerns (hop-counter reduction,
 * remote-subsystem availability, loadshare secondary selection, MTP routing)
 * are intentionally out of scope here. Not yet wired into
 * {@code StpTransitApplyService} — that happens when the apply path adopts the
 * fixtures.</p>
 *
 * <p><b>Statelessness guarantee (DESIGN §9.4.2).</b> GTT rewrites the CdPA
 * only. The harness rejects any rule carrying a {@code newCallingPartyAddress}
 * at construction time, so {@link #translate} can never touch the CgPA — every
 * message keeps the true originator GT and stays self-routing on the way back.</p>
 *
 * <p><b>Determinism.</b> jSS7's {@code RuleComparator} documents a
 * most-specific-first ordering (exact digits before {@code ?} wildcards before
 * {@code *}), but its asterisk branch is asymmetric — for an exact vs wildcard
 * pair, {@code compare(exact, wildcard)} and {@code compare(wildcard, exact)}
 * can both return a negative value — and the runtime {@code RouterExtImpl}
 * lookup iterates a {@code HashMap} whose order is unspecified anyway. The
 * harness therefore enforces the documented most-specific-first order itself
 * ({@link #compareSpecificity}) so overlapping fixtures (e.g. an exact
 * shortcode inside a wildcard range) resolve reproducibly.</p>
 */
public final class GttHarness {
    private static final Logger LOG = LogManager.getLogger(GttHarness.class);

    private final GttFixtures fixtures;
    private final List<RuleImpl> rulesBySpecificity;
    private final Map<Integer, SccpAddress> routingAddresses;
    private final Map<Integer, String> serviceByRuleId;

    private GttHarness(GttFixtures fixtures) {
        this.fixtures = fixtures;
        Map<Integer, SccpAddress> addresses = new HashMap<>();
        Map<Integer, String> services = new HashMap<>();
        List<RuleImpl> rules = new java.util.ArrayList<>(fixtures.rules().size());
        for (GttFixtures.GttRule fr : fixtures.rules()) {
            SccpAddress pattern = new SccpAddressImpl(
                    RoutingIndicator.ROUTING_BASED_ON_GLOBAL_TITLE,
                    new GlobalTitle0001Impl(fr.gtPattern(), fr.natureOfAddress()),
                    0, 0, fr.networkId());
            RuleImpl rule = new RuleImpl(RuleType.SOLITARY, LoadSharingAlgorithm.Undefined,
                    OriginationType.ALL, pattern, derivedMask(fr.gtPattern()),
                    fr.networkId(), /* patternCallingAddress */ null);
            rule.setRuleId(fr.ruleId());
            rule.setPrimaryAddressId(fr.ruleId());
            if (rule.getNewCallingPartyAddressId() != null) {
                // DESIGN §9.4.2: GTT must never rewrite the CgPA — a rule with
                // NewCallingPartyAddressId would break stateless return routing.
                throw new IllegalStateException(
                        "gtt rule " + fr.ruleId() + ": newCallingPartyAddress is forbidden");
            }
            // Translation address: hidden DPC+SSN; GT consumed by the derived
            // mask ("R/-/…" vs "-/-…"), mirroring jSS7 RuleTest.testTranslate1.
            SccpAddress primary = new SccpAddressImpl(
                    RoutingIndicator.ROUTING_BASED_ON_DPC_AND_SSN,
                    new GlobalTitle0010Impl(derivedTranslationDigits(fr.gtPattern()), 0),
                    fr.dpc(), fr.ssn(), fr.networkId());
            addresses.put(fr.ruleId(), primary);
            services.put(fr.ruleId(), fr.service());
            rules.add(rule);
        }
        rules.sort(GttHarness::compareSpecificity);
        this.rulesBySpecificity = List.copyOf(rules);
        this.routingAddresses = Map.copyOf(addresses);
        this.serviceByRuleId = Map.copyOf(services);
        LOG.info("GTT harness built: {} rule(s), networkId default {}, negatives {}",
                rulesBySpecificity.size(), fixtures.networkId(), fixtures.negativeGts().size());
    }

    public static GttHarness of(GttFixtures fixtures) {
        return new GttHarness(Objects.requireNonNull(fixtures, "fixtures"));
    }

    /** Strict-parse fixture JSON (module convention, mirrors StpTransitConfigLoader). */
    public static GttHarness parse(String json) throws IOException {
        return new GttHarness(GttFixtureLoader.parse(json));
    }

    public static GttHarness load(Path file) throws IOException {
        return new GttHarness(GttFixtureLoader.load(file));
    }

    /**
     * Resolve a called-party GT given as digits (convenience overload; the GT
     * is built GTI=0001, NOA=INTERNATIONAL, GT-routing, no PC/SSN). Use the
     * {@link SccpAddress} overloads for other nature-of-address settings.
     *
     * @param calledPartyGtDigits called-party GT digits ({@code [0-9]+})
     * @param ss7NetworkId        jSS7 networkId the message arrived on
     * @return resolved hidden DPC+SSN or {@link GttResult.Status#UNRESOLVED}
     */
    public GttResult resolve(String calledPartyGtDigits, int ss7NetworkId) {
        if (calledPartyGtDigits == null || calledPartyGtDigits.isEmpty()
                || !calledPartyGtDigits.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException(
                    "calledPartyGtDigits must be a non-empty digit string, got '"
                            + calledPartyGtDigits + "'");
        }
        SccpAddress cdpa = new SccpAddressImpl(
                RoutingIndicator.ROUTING_BASED_ON_GLOBAL_TITLE,
                new GlobalTitle0001Impl(calledPartyGtDigits, NatureOfAddress.INTERNATIONAL),
                0, 0, ss7NetworkId);
        return resolve(cdpa, null, ss7NetworkId);
    }

    public GttResult resolve(SccpAddress calledParty, int ss7NetworkId) {
        return resolve(calledParty, null, ss7NetworkId);
    }

    /**
     * First-match lookup over the fixture rules in specificity order, mirroring
     * {@code RouterExtImpl.findRule}. The {@code callingParty} only influences
     * matching when a rule carries a patternCallingAddress — none of our
     * fixtures do (DESIGN §9.4.2 posture), so it passes through untouched.
     */
    public GttResult resolve(SccpAddress calledParty, SccpAddress callingParty, int ss7NetworkId) {
        Objects.requireNonNull(calledParty, "calledParty");
        for (RuleImpl rule : rulesBySpecificity) {
            if (rule.matches(calledParty, callingParty, /* isMtpOriginated */ false, ss7NetworkId)) {
                SccpAddress primary = routingAddresses.get(rule.getPrimaryAddressId());
                SccpAddress translated = rule.translate(calledParty, primary);
                LOG.debug("GTT resolved: ruleId={} service={} -> dpc={} ssn={}",
                        rule.getRuleId(), serviceByRuleId.get(rule.getRuleId()),
                        translated.getSignalingPointCode(), translated.getSubsystemNumber());
                return GttResult.resolved(
                        rule.getRuleId(), serviceByRuleId.get(rule.getRuleId()), translated);
            }
        }
        LOG.debug("GTT unresolved: cdpa={} networkId={}", calledParty, ss7NetworkId);
        return GttResult.unresolved();
    }

    /**
     * Address-rewrite half of {@code Ss7ExtSccpDetailedImpl.translationFunction}:
     * returns the translated CdPA, or {@code null} when unresolved.
     *
     * <p><b>Contract (DESIGN §9.4.2):</b> the {@code callingParty} argument is
     * never mutated, replaced or returned — translation touches the CdPA only.
     * Enforced structurally: no harness rule may set newCallingPartyAddressId.</p>
     */
    public SccpAddress translate(SccpAddress calledParty, SccpAddress callingParty, int ss7NetworkId) {
        GttResult r = resolve(calledParty, callingParty, ss7NetworkId);
        return r.resolved() ? r.translatedCalledParty() : null;
    }

    /** True if any loaded rule would rewrite the CgPA — always {@code false} here. */
    public boolean rewritesCallingParty() {
        return rulesBySpecificity.stream().anyMatch(r -> r.getNewCallingPartyAddressId() != null);
    }

    public GttFixtures fixtures() {
        return fixtures;
    }

    /**
     * Most-specific-first ordering per the {@code RuleComparator} javadoc
     * contract: exact digits before {@code ?} wildcards before {@code *};
     * among {@code *} patterns the one whose first {@code *} appears later is
     * more specific; ties broken by ruleId for full determinism.
     */
    private static int compareSpecificity(RuleImpl r1, RuleImpl r2) {
        String d1 = r1.getPattern().getGlobalTitle().getDigits().replace("/", "");
        String d2 = r2.getPattern().getGlobalTitle().getDigits().replace("/", "");
        int rankDiff = Integer.compare(wildcardRank(d1), wildcardRank(d2));
        if (rankDiff != 0) {
            return rankDiff;
        }
        if (wildcardRank(d1) == 0) {
            // two exact patterns: shorter first, then lexicographic
            int c = Integer.compare(d1.length(), d2.length());
            if (c != 0) {
                return c;
            }
            c = d1.compareTo(d2);
            if (c != 0) {
                return c;
            }
        } else if (d1.indexOf('*') >= 0) {
            // two '*' patterns: later first '*' = longer fixed prefix = more specific
            int c = Integer.compare(d2.indexOf('*'), d1.indexOf('*'));
            if (c != 0) {
                return c;
            }
        } else {
            // two '?' patterns: fewer wildcards first
            int c = Integer.compare(countChar(d1, '?'), countChar(d2, '?'));
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(r1.getRuleId(), r2.getRuleId());
    }

    private static int wildcardRank(String digits) {
        if (digits.indexOf('*') >= 0) {
            return 2;
        }
        return digits.indexOf('?') >= 0 ? 1 : 0;
    }

    private static int countChar(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    /** Mask replacing every pattern section (GT consumed → DPC+SSN routing). */
    private static String derivedMask(String gtPattern) {
        int sections = gtPattern.split("/", -1).length;
        StringBuilder sb = new StringBuilder("R");
        for (int i = 1; i < sections; i++) {
            sb.append("/-");
        }
        return sb.toString();
    }

    /** Translation-address GT: one ignore ("-") marker per pattern section. */
    private static String derivedTranslationDigits(String gtPattern) {
        int sections = gtPattern.split("/", -1).length;
        return String.join("/", Collections.nCopies(sections, "-"));
    }
}