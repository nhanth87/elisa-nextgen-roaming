package et.elisa.iwf.diameter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PlmnTest {

    @Test
    void twoDigitMncEncodesWithFiller() {
        assertArrayEquals(new byte[] {0x54, (byte) 0xF2, 0x40}, Plmn.tbcd("45204"));
    }

    @Test
    void threeDigitMncEncodesAllDigits() {
        assertArrayEquals(new byte[] {0x54, 0x02, 0x40}, Plmn.tbcd("452040"));
        assertArrayEquals(new byte[] {0x54, (byte) 0xF2, 0x10}, Plmn.tbcd("45201"));
    }

    @Test
    void specExample34512MatchesTs24301() {
        assertArrayEquals(new byte[] {0x43, (byte) 0xF5, 0x21}, Plmn.tbcd("34512"));
    }

    @Test
    void nonDigitsAreIgnored() {
        assertArrayEquals(Plmn.tbcd("45204"), Plmn.tbcd("452-04"));
    }

    @Test
    void validityChecksLength() {
        assertTrue(Plmn.isValid("45204"));
        assertTrue(Plmn.isValid("452040"));
        assertFalse(Plmn.isValid("452"));
        assertFalse(Plmn.isValid(""));
        assertFalse(Plmn.isValid(null));
    }
}
