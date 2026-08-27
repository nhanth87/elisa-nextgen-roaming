package et.elisa.iwf.diameter;

/**
 * Visited-PLMN-Id (AVP 1407, TS 29.272) wire encoding: 3-octet TBCD per
 * TS 24.301 §9.9.3.12 — octet1 = MCC2|MCC1, octet2 = MNC3|MCC3,
 * octet3 = MNC2|MNC1 (MNC3 = 0xF for 2-digit MNC).
 */
public final class Plmn {

    private Plmn() {
    }

    /** "45204" (MCC+MNC, 5–6 digits) → 3 TBCD bytes. */
    public static byte[] tbcd(String mccMnc) {
        String digits = normalize(mccMnc);
        int mcc1 = digits.charAt(0) - '0';
        int mcc2 = digits.charAt(1) - '0';
        int mcc3 = digits.charAt(2) - '0';
        int mnc1 = Character.digit(digits.charAt(3), 16);
        int mnc2 = Character.digit(digits.charAt(4), 16);
        int mnc3 = digits.length() == 6 ? Character.digit(digits.charAt(5), 16) : 0xF;
        return new byte[] {
                (byte) ((mcc2 << 4) | mcc1),
                (byte) ((mnc3 << 4) | mcc3),
                (byte) ((mnc2 << 4) | mnc1),
        };
    }

    public static boolean isValid(String mccMnc) {
        if (mccMnc == null) {
            return false;
        }
        String digits = mccMnc.replaceAll("[^0-9]", "");
        return digits.length() == 5 || digits.length() == 6;
    }

    private static String normalize(String mccMnc) {
        if (mccMnc == null) {
            throw new IllegalArgumentException("plmn required");
        }
        String digits = mccMnc.replaceAll("[^0-9]", "");
        if (digits.length() != 5 && digits.length() != 6) {
            throw new IllegalArgumentException(
                    "plmn must be MCC+MNC (5-6 digits): " + mccMnc);
        }
        return digits;
    }
}
