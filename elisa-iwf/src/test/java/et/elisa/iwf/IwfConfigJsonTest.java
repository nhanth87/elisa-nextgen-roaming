package et.elisa.iwf;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class IwfConfigJsonTest {

    @Test
    void parsesFullConfig() {
        IwfConfig c = IwfConfigJson.parse("""
                {"diameter":{"draHost":"10.1.1.5","draPort":3870,"srcPort":38690,
                  "originHost":"iwf1.lab","originRealm":"lab",
                  "destHost":"dra1.lab","destRealm":"lab",
                  "responseTimeoutMillis":3000},
                 "map":{"ssn":147,"ownGt":"8860123","ownSpc":"123"}}
                """);
        assertEquals("10.1.1.5", c.diameter().draHost());
        assertEquals(3870, c.diameter().draPort());
        assertEquals(38690, c.diameter().srcPort());
        assertEquals("dra1.lab", c.diameter().destHost());
        assertEquals(3000, c.diameter().responseTimeoutMillis());
        assertEquals(147, c.map().ssn());
    }

    @Test
    void defaultsWhenSectionsMissing() {
        IwfConfig c = IwfConfigJson.parse("{}");
        assertEquals("127.0.0.1", c.diameter().draHost());
        assertEquals(38690, c.diameter().srcPort());
        assertEquals(5000, c.diameter().responseTimeoutMillis());
        assertEquals(146, c.map().ssn());
    }

    @Test
    void rejectsInvalidDraPort() {
        try {
            IwfConfigJson.parse("{\"diameter\":{\"draHost\":\"h\",\"draPort\":99999}}");
            org.junit.jupiter.api.Assertions.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            org.junit.jupiter.api.Assertions.assertTrue(
                    expected.getMessage().contains("draPort"));
        }
    }

    @Test
    void rejectsMapSsnZero() {
        try {
            new IwfConfig.MapLegConfig(0, "8860123", "123");
            org.junit.jupiter.api.Assertions.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            org.junit.jupiter.api.Assertions.assertTrue(
                    expected.getMessage().contains("ssn"));
        }
    }

    @Test
    void rejectsMapSsnOutOfRange() {
        try {
            new IwfConfig.MapLegConfig(256, "8860123", "123");
            org.junit.jupiter.api.Assertions.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            org.junit.jupiter.api.Assertions.assertTrue(
                    expected.getMessage().contains("ssn"));
        }
    }

    @Test
    void rejectsTbdGt() {
        try {
            new IwfConfig.MapLegConfig(146, "TBD-GT", "123");
            org.junit.jupiter.api.Assertions.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            org.junit.jupiter.api.Assertions.assertTrue(
                    expected.getMessage().contains("TBD"));
        }
    }

    @Test
    void rejectsTbdSpc() {
        try {
            new IwfConfig.MapLegConfig(146, "8860123", "TBD-SPC");
            org.junit.jupiter.api.Assertions.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            org.junit.jupiter.api.Assertions.assertTrue(
                    expected.getMessage().contains("TBD"));
        }
    }

    @Test
    void acceptsValidMapLegConfig() {
        IwfConfig.MapLegConfig cfg = new IwfConfig.MapLegConfig(146, "88601234560", "250");
        org.junit.jupiter.api.Assertions.assertEquals(146, cfg.ssn());
        org.junit.jupiter.api.Assertions.assertEquals("88601234560", cfg.ownGt());
        org.junit.jupiter.api.Assertions.assertEquals("250", cfg.ownSpc());
    }
}
