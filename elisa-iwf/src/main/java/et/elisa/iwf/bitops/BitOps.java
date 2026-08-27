package et.elisa.iwf.bitops;

/**
 * Scalar bitwise reference implementations (AND/OR/XOR/NOT/nibble ops).
 * Every {@link SimdBitOps} result is asserted equal against this class in
 * tests — scalar stays the single source of truth for correctness.
 */
public final class BitOps {

    private BitOps() {
    }

    public static long[] and(long[] a, long[] b) {
        requireSameLength(a, b);
        long[] out = new long[a.length];
        for (int i = 0; i < a.length; i++) {
            out[i] = a[i] & b[i];
        }
        return out;
    }

    public static long[] or(long[] a, long[] b) {
        requireSameLength(a, b);
        long[] out = new long[a.length];
        for (int i = 0; i < a.length; i++) {
            out[i] = a[i] | b[i];
        }
        return out;
    }

    public static long[] xor(long[] a, long[] b) {
        requireSameLength(a, b);
        long[] out = new long[a.length];
        for (int i = 0; i < a.length; i++) {
            out[i] = a[i] ^ b[i];
        }
        return out;
    }

    public static long[] not(long[] a) {
        long[] out = new long[a.length];
        for (int i = 0; i < a.length; i++) {
            out[i] = ~a[i];
        }
        return out;
    }

    public static byte[] xorBytes(byte[] a, byte[] b) {
        requireSameLength(a, b);
        byte[] out = new byte[a.length];
        for (int i = 0; i < a.length; i++) {
            out[i] = (byte) (a[i] ^ b[i]);
        }
        return out;
    }

    /** Swap nibbles of every byte (TBCD ⇄ digit order primitive). */
    public static byte[] nibbleSwap(byte[] a) {
        byte[] out = new byte[a.length];
        for (int i = 0; i < a.length; i++) {
            int v = a[i] & 0xFF;
            out[i] = (byte) (((v >> 4) | (v << 4)) & 0xFF);
        }
        return out;
    }

    public static long popcount(long[] a) {
        long total = 0;
        for (long v : a) {
            total += Long.bitCount(v);
        }
        return total;
    }

    static void requireSameLength(long[] a, long[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException(
                    "length mismatch: " + a.length + " vs " + b.length);
        }
    }

    static void requireSameLength(byte[] a, byte[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException(
                    "length mismatch: " + a.length + " vs " + b.length);
        }
    }
}
