package et.elisa.stp.gtt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.restcomm.protocols.ss7.indicator.NatureOfAddress;
import org.restcomm.protocols.ss7.indicator.RoutingIndicator;
import org.restcomm.protocols.ss7.sccp.impl.parameter.GlobalTitle0001Impl;
import org.restcomm.protocols.ss7.sccp.impl.parameter.SccpAddressImpl;
import org.restcomm.protocols.ss7.sccp.parameter.SccpAddress;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * GTT harness tests — DESIGN §10.2 P5+P7 adaptation, backed by the per-service
 * fixture JSON ({@code gtt-fixtures.json}): USSD 2519* → hidden 201+SSN 251,
 * SMSC 2511* → hidden 202+SSN 8, GMLC 2512* → hidden 203+SSN 145, plus an
 * exact-match USSD shortcode and a negative (never-matching) GT range.
 */
class GttHarnessTest {

    private static GttHarness harness() throws IOException {
        try (InputStream in = GttHarnessTest.class.getResourceAsStream("/gtt-fixtures.json")) {
            assertThat(in).as("gtt-fixtures.json on the test classpath").isNotNull();
            return GttHarness.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /** GT-routed SCCP address, GTI=0001, no PC/SSN — like a peer-originated UDT CdPA/CgPA. */
    private static SccpAddress gt(String digits) {
        return new SccpAddressImpl(RoutingIndicator.ROUTING_BASED_ON_GLOBAL_TITLE,
                new GlobalTitle0001Impl(digits, NatureOfAddress.INTERNATIONAL), 0, 0);
    }

    @Test
    void fixtureJsonLoadsAllServiceRanges() throws IOException {
        GttHarness h = harness();
        assertThat(h.fixtures().rules()).hasSize(4);
        assertThat(h.fixtures().networkId()).isZero();
        assertThat(h.fixtures().negativeGts()).hasSize(4);
        assertThat(h.fixtures().rules()).extracting(GttFixtures.GttRule::service)
                .containsExactly("USSD", "USSD", "SMSC", "GMLC");
    }

    @Test
    void ussdRangeResolvesToHiddenUssdCluster() throws IOException {
        GttResult r = harness().resolve("25199876543", 0);
        assertThat(r.resolved()).isTrue();
        assertThat(r.ruleId()).isEqualTo(2);
        assertThat(r.service()).isEqualTo("USSD");
        assertThat(r.dpc()).isEqualTo(201);
        assertThat(r.ssn()).isEqualTo(251);
        // GT consumed: translated CdPA routes on hidden DPC+SSN only
        assertThat(r.translatedCalledParty().getAddressIndicator().getRoutingIndicator())
                .isEqualTo(RoutingIndicator.ROUTING_BASED_ON_DPC_AND_SSN);
        assertThat(r.translatedCalledParty().getGlobalTitle()).isNull();
        assertThat(r.translatedCalledParty().isTranslated()).isTrue();
    }

    @Test
    void smscRangeResolvesToHiddenSmscCluster() throws IOException {
        GttResult r = harness().resolve("251100000007", 0);
        assertThat(r.resolved()).isTrue();
        assertThat(r.ruleId()).isEqualTo(3);
        assertThat(r.service()).isEqualTo("SMSC");
        assertThat(r.dpc()).isEqualTo(202);
        assertThat(r.ssn()).isEqualTo(8);
    }

    @Test
    void gmlcRangeResolvesToHiddenGmlcCluster() throws IOException {
        GttResult r = harness().resolve("251255512345", 0);
        assertThat(r.resolved()).isTrue();
        assertThat(r.ruleId()).isEqualTo(4);
        assertThat(r.service()).isEqualTo("GMLC");
        assertThat(r.dpc()).isEqualTo(203);
        assertThat(r.ssn()).isEqualTo(145);
    }

    @Test
    void exactMatchBeatsWildcardRange() throws IOException {
        GttHarness h = harness();
        // exact shortcode (rule 1) sits inside the USSD wildcard range (rule 2)
        GttResult exact = h.resolve("2519123456", 0);
        assertThat(exact.resolved()).isTrue();
        assertThat(exact.ruleId()).isEqualTo(1);
        assertThat(exact.dpc()).isEqualTo(204);
        assertThat(exact.ssn()).isEqualTo(251);
        // one digit longer/shorter than the exact GT falls back to the wildcard
        assertThat(h.resolve("25191234567", 0).ruleId()).isEqualTo(2);
        assertThat(h.resolve("25191234567", 0).dpc()).isEqualTo(201);
        assertThat(h.resolve("251912345", 0).ruleId()).isEqualTo(2);
        // the exact rule never matches longer GTs (no implicit wildcard)
        assertThat(h.resolve("251912345678901", 0).ruleId()).isEqualTo(2);
    }

    @Test
    void wildcardRangeBoundaries() throws IOException {
        GttHarness h = harness();
        // prefix alone still matches the 2519/* range (jSS7 special case)
        assertThat(h.resolve("2519", 0).dpc()).isEqualTo(201);
        // too short for any fixture prefix: no rule can match
        assertThat(h.resolve("251", 0).resolved()).isFalse();
        assertThat(h.resolve("2", 0).resolved()).isFalse();
    }

    @Test
    void negativeRangeNeverResolves() throws IOException {
        GttHarness h = harness();
        for (String gt : h.fixtures().negativeGts()) {
            GttResult r = h.resolve(gt, 0);
            assertThat(r.resolved()).as("negative GT %s must stay unresolved", gt).isFalse();
            assertThat(r.status()).isEqualTo(GttResult.Status.UNRESOLVED);
            assertThat(r.dpc()).isEqualTo(-1);
            assertThat(r.ssn()).isEqualTo(-1);
            assertThat(r.translatedCalledParty()).isNull();
            assertThat(h.translate(gt(gt), null, 0)).isNull();
        }
    }

    @Test
    void networkIdPartitionsTheRuleTable() throws IOException {
        GttHarness h = harness();
        // fixtures live in networkId 0 — other networks see no rules (DESIGN §3)
        assertThat(h.resolve("25199876543", 0).resolved()).isTrue();
        assertThat(h.resolve("25199876543", 1).resolved()).isFalse();
        assertThat(h.resolve("251100000007", 7).resolved()).isFalse();
    }

    /**
     * CRITICAL statelessness assertion — DESIGN §9.4.2: GTT rewrites the CdPA
     * only; the CgPA must stay untouched so return traffic remains self-routing
     * and the transit plane keeps zero per-message state.
     */
    @Test
    void translationNeverTouchesCallingPartyAddress() throws IOException {
        GttHarness h = harness();
        SccpAddress cdpa = gt("25199876543");
        SccpAddress cgpa = gt("251811222333");
        SccpAddress cgpaReference = cgpa;

        GttResult r = h.resolve(cdpa, cgpa, 0);
        assertThat(r.resolved()).isTrue();

        // object identity: the CgPA is never swapped for another object
        assertThat(cgpa).isSameAs(cgpaReference);
        // value equality: CgPA content is byte-for-byte unchanged
        assertThat(cgpa).isEqualTo(gt("251811222333"));
        assertThat(cgpa.getGlobalTitle().getDigits()).isEqualTo("251811222333");
        assertThat(cgpa.getSignalingPointCode()).isZero();
        assertThat(cgpa.getSubsystemNumber()).isZero();
        // structural: no fixture rule carries a newCallingPartyAddress
        assertThat(h.rewritesCallingParty()).isFalse();
        // only the CdPA is rewritten — a new, translated object
        assertThat(r.translatedCalledParty()).isNotSameAs(cdpa);
        assertThat(r.translatedCalledParty().isTranslated()).isTrue();
        assertThat(r.translatedCalledParty().getSignalingPointCode()).isEqualTo(201);

        // translate() path honours the same contract
        SccpAddress cgpaSnapshot = gt("251811222333");
        SccpAddress translated = h.translate(cdpa, cgpa, 0);
        assertThat(translated).isNotNull();
        assertThat(cgpa).isSameAs(cgpaReference).isEqualTo(cgpaSnapshot);
        // unresolved translation returns null and still leaves the CgPA alone
        assertThat(h.translate(gt("9999"), cgpa, 0)).isNull();
        assertThat(cgpa).isSameAs(cgpaReference).isEqualTo(cgpaSnapshot);
    }

    @Test
    void rejectsInvalidCalledPartyDigits() throws IOException {
        GttHarness h = harness();
        assertThrows(IllegalArgumentException.class, () -> h.resolve("2519abc", 0));
        assertThrows(IllegalArgumentException.class, () -> h.resolve("", 0));
        assertThrows(IllegalArgumentException.class, () -> h.resolve((String) null, 0));
        assertThrows(IllegalArgumentException.class, () -> h.resolve("2519*", 0));
    }

    @Test
    void unknownFixtureFieldsAreRejected() {
        // module convention (StpTransitConfigLoader): strict JSON, typos fail fast
        assertThrows(IOException.class, () -> GttHarness.parse("""
                {
                  "schema": "stp.gtt.fixtures/v1",
                  "networkId": 0,
                  "bogusField": true,
                  "rules": [
                    { "ruleId": 1, "service": "USSD", "gtPattern": "2519/*",
                      "natureOfAddress": "INTERNATIONAL", "dpc": 201, "ssn": 251 }
                  ]
                }
                """));
    }

    /**
     * (e) Hop-counter semantics: NOT testable at this layer — skipped by design.
     *
     * <p>{@code Ss7ExtSccpDetailedImpl.translationFunction} reduces the hop
     * counter on the {@code SccpAddressedMessageImpl} BEFORE rule lookup
     * ({@code msg.reduceHopCounter()} → {@code HOP_COUNTER_VIOLATION} on
     * underflow). That requires a full encoded SCCP message plus the stack
     * context ({@code SccpRoutingCtxInterface}); the harness operates purely on
     * rule objects and addresses. Hop-counter behaviour must be covered by the
     * stack-level integration tests (jvm-harness profile), not here.</p>
     */
    @Test
    @Disabled("hop counter lives at stack level (translationFunction msg.reduceHopCounter), "
            + "not in the rule/address layer this harness covers")
    void hopCounterSemanticsAreStackLevel() {
    }
}