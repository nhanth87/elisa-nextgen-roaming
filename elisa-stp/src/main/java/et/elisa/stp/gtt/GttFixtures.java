package et.elisa.stp.gtt;

import java.util.List;

import org.restcomm.protocols.ss7.indicator.NatureOfAddress;

/**
 * Per-service GTT fixture set (DESIGN §3 numbering-plan template, §10.2 P5+P7).
 *
 * <p>Pure immutable model loaded from JSON by {@link GttFixtureLoader}; the
 * {@link GttHarness} turns each {@link GttRule} into a jSS7
 * {@code org.restcomm.protocols.ss7.sccpext.impl.router.RuleImpl}.</p>
 *
 * <p>Semantics per DESIGN §3: internal service PCs (range 200–299 in the
 * reference plan) are hidden — peers only ever see GT ranges plus the single
 * logical STP PC; GTT maps a called GT onto the hidden DPC+SSN. GTT rewrites
 * the CdPA only; the CgPA is never touched (DESIGN §9.4.2), which keeps the
 * transit plane stateless.</p>
 *
 * @param schema      fixture schema stamp (must be {@code stp.gtt.fixtures/v1})
 * @param description human-readable fixture description (optional)
 * @param networkId   default jSS7 {@code networkId} for rules that do not
 *                    override it (DESIGN §3 networkId scheme; {@code 0} default)
 * @param rules       per-service GT rules (at least one)
 * @param negativeGts GTs that must NOT resolve under {@link #rules} — the
 *                    negative range used to assert UNRESOLVED behaviour
 */
public record GttFixtures(
        String schema,
        String description,
        int networkId,
        List<GttRule> rules,
        List<String> negativeGts) {

    public static final String SCHEMA_V1 = "stp.gtt.fixtures/v1";

    public GttFixtures {
        if (schema == null || schema.isBlank()) {
            throw new IllegalArgumentException("gtt fixtures: schema required");
        }
        if (!SCHEMA_V1.equals(schema.trim())) {
            throw new IllegalArgumentException("gtt fixtures: unsupported schema " + schema);
        }
        if (networkId < 0) {
            throw new IllegalArgumentException("gtt fixtures: networkId must be >= 0");
        }
        if (rules == null || rules.isEmpty()) {
            throw new IllegalArgumentException("gtt fixtures: at least one rule required");
        }
        rules = List.copyOf(rules);
        java.util.Set<Integer> ids = new java.util.HashSet<>();
        for (GttRule r : rules) {
            if (!ids.add(r.ruleId())) {
                throw new IllegalArgumentException("gtt fixtures: duplicate ruleId " + r.ruleId());
            }
        }
        negativeGts = negativeGts == null ? List.of() : List.copyOf(negativeGts);
        for (String gt : negativeGts) {
            if (gt == null || gt.isEmpty() || !gt.chars().allMatch(Character::isDigit)) {
                throw new IllegalArgumentException(
                        "gtt fixtures: negativeGts must be non-empty digit strings, got '" + gt + "'");
            }
        }
    }

    /**
     * One per-service GT rule.
     *
     * <p>{@code gtPattern} uses the jSS7-native pattern grammar
     * ({@code *} = rest of digits, {@code ?} = one digit, {@code /} = mask
     * section separator), e.g. {@code 2519/*} for "GT prefix 2519, any rest".</p>
     *
     * <p>The harness always translates GT → hidden DPC+SSN (the GT is consumed,
     * mirroring DESIGN §9.3 "GTT: GT → DPC, SSN"): the derived mask replaces
     * the digits, the translated CdPA routes on DPC_AND_SSN.</p>
     *
     * @param ruleId          unique rule id (also the primary routing-address id)
     * @param service         owning service cluster (e.g. USSD, SMSC, GMLC)
     * @param description     human-readable intent (optional)
     * @param gtPattern       jSS7 pattern digits incl. wildcards (must contain a digit)
     * @param gti             global-title indicator; only {@code 0001} supported
     * @param natureOfAddress nature of address the pattern matches (and that
     *                        resolved called GTs must carry)
     * @param dpc             hidden destination point code (internal range, never
     *                        advertised to peers)
     * @param ssn             hidden subsystem number appearing after GTT
     * @param networkId       jSS7 networkId this rule belongs to
     */
    public record GttRule(
            int ruleId,
            String service,
            String description,
            String gtPattern,
            String gti,
            NatureOfAddress natureOfAddress,
            int dpc,
            int ssn,
            int networkId) {

        private static final java.util.regex.Pattern VALID_PATTERN =
                java.util.regex.Pattern.compile("[0-9*?/]+");

        public GttRule {
            if (ruleId < 1) {
                throw new IllegalArgumentException("gtt rule: ruleId must be >= 1");
            }
            if (service == null || service.isBlank()) {
                throw new IllegalArgumentException("gtt rule " + ruleId + ": service required");
            }
            service = service.trim();
            if (gtPattern == null || gtPattern.isBlank()) {
                throw new IllegalArgumentException("gtt rule " + ruleId + ": gtPattern required");
            }
            gtPattern = gtPattern.trim();
            if (!VALID_PATTERN.matcher(gtPattern).matches()) {
                throw new IllegalArgumentException("gtt rule " + ruleId
                        + ": gtPattern may only contain digits, '*', '?' and '/': " + gtPattern);
            }
            if (gtPattern.chars().noneMatch(Character::isDigit)) {
                throw new IllegalArgumentException(
                        "gtt rule " + ruleId + ": gtPattern must contain at least one digit");
            }
            for (String section : gtPattern.split("/", -1)) {
                if (section.isEmpty()) {
                    throw new IllegalArgumentException(
                            "gtt rule " + ruleId + ": empty '/' section in gtPattern " + gtPattern);
                }
                // RuleImpl.translate() splits digit components per section and only
                // honours '*' as "take the rest" — restrict '*' to the section end.
                int star = section.indexOf('*');
                if (star >= 0 && (star != section.length() - 1
                        || section.indexOf('*', star + 1) >= 0)) {
                    throw new IllegalArgumentException("gtt rule " + ruleId
                            + ": '*' only allowed at the end of a '/' section: " + gtPattern);
                }
            }
            if (gti == null || gti.isBlank()) {
                gti = "0001";
            }
            if (!"0001".equals(gti.trim())) {
                throw new IllegalArgumentException("gtt rule " + ruleId
                        + ": only gti=0001 (nature-of-address only) is supported, got " + gti);
            }
            gti = gti.trim();
            if (natureOfAddress == null) {
                throw new IllegalArgumentException(
                        "gtt rule " + ruleId + ": natureOfAddress required");
            }
            if (dpc < 1 || dpc > 16383) {
                throw new IllegalArgumentException(
                        "gtt rule " + ruleId + ": dpc out of range (1..16383): " + dpc);
            }
            if (ssn < 1 || ssn > 255) {
                throw new IllegalArgumentException(
                        "gtt rule " + ruleId + ": ssn out of range (1..255): " + ssn);
            }
            if (networkId < 0) {
                throw new IllegalArgumentException(
                        "gtt rule " + ruleId + ": networkId must be >= 0");
            }
        }
    }
}