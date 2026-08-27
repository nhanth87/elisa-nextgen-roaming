package et.elisa.iwf.bitops;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * SIMD (Vector API, jdk.incubator.vector) bitwise kernels for the IWF hot
 * paths: flag-mask batch evaluation and TBCD nibble swaps. Results are
 * asserted equal to {@link BitOps} in tests; fall back to scalar for tails.
 */
public final class SimdBitOps {

    private static final VectorSpecies<Long> LONG_SPEC =
            LongVector.SPECIES_PREFERRED;
    private static final VectorSpecies<Byte> BYTE_SPEC =
            ByteVector.SPECIES_PREFERRED;

    private SimdBitOps() {
    }

    public static String speciesLong() {
        return LONG_SPEC.toString();
    }

    public static String speciesByte() {
        return BYTE_SPEC.toString();
    }

    public static long[] and(long[] a, long[] b) {
        return longOp(a, b, VectorOperators.AND, (x, y) -> x & y);
    }

    public static long[] or(long[] a, long[] b) {
        return longOp(a, b, VectorOperators.OR, (x, y) -> x | y);
    }

    public static long[] xor(long[] a, long[] b) {
        return longOp(a, b, VectorOperators.XOR, (x, y) -> x ^ y);
    }

    private static long[] longOp(long[] a, long[] b, VectorOperators.Binary op,
                                 java.util.function.LongBinaryOperator scalar) {
        BitOps.requireSameLength(a, b);
        long[] out = new long[a.length];
        int i = 0;
        int bound = LONG_SPEC.loopBound(a.length);
        for (; i < bound; i += LONG_SPEC.length()) {
            LongVector va = LongVector.fromArray(LONG_SPEC, a, i);
            LongVector vb = LongVector.fromArray(LONG_SPEC, b, i);
            va.lanewise(op, vb).intoArray(out, i);
        }
        for (; i < a.length; i++) {
            out[i] = scalar.applyAsLong(a[i], b[i]);
        }
        return out;
    }

    public static long[] not(long[] a) {
        long[] out = new long[a.length];
        int i = 0;
        int bound = LONG_SPEC.loopBound(a.length);
        for (; i < bound; i += LONG_SPEC.length()) {
            LongVector.fromArray(LONG_SPEC, a, i).lanewise(VectorOperators.NOT)
                    .intoArray(out, i);
        }
        for (; i < a.length; i++) {
            out[i] = ~a[i];
        }
        return out;
    }

    public static byte[] xorBytes(byte[] a, byte[] b) {
        BitOps.requireSameLength(a, b);
        byte[] out = new byte[a.length];
        int i = 0;
        int bound = BYTE_SPEC.loopBound(a.length);
        for (; i < bound; i += BYTE_SPEC.length()) {
            ByteVector va = ByteVector.fromArray(BYTE_SPEC, a, i);
            ByteVector vb = ByteVector.fromArray(BYTE_SPEC, b, i);
            va.lanewise(VectorOperators.XOR, vb).intoArray(out, i);
        }
        System.arraycopy(BitOps.xorBytes(a, b), i, out, i, a.length - i);
        return out;
    }

    /** Nibble swap across every byte — TBCD ⇄ digit-order bulk transform. */
    public static byte[] nibbleSwap(byte[] a) {
        byte[] out = new byte[a.length];
        int i = 0;
        int bound = BYTE_SPEC.loopBound(a.length);
        for (; i < bound; i += BYTE_SPEC.length()) {
            ByteVector v = ByteVector.fromArray(BYTE_SPEC, a, i);
            v.lanewise(VectorOperators.LSHR, 4)
                    .lanewise(VectorOperators.OR,
                            v.lanewise(VectorOperators.LSHL, 4))
                    .intoArray(out, i);
        }
        System.arraycopy(BitOps.nibbleSwap(a), i, out, i, a.length - i);
        return out;
    }

    public static long popcount(long[] a) {
        long total = 0;
        int i = 0;
        int bound = LONG_SPEC.loopBound(a.length);
        for (; i < bound; i += LONG_SPEC.length()) {
            total += LongVector.fromArray(LONG_SPEC, a, i)
                    .lanewise(VectorOperators.BIT_COUNT).reduceLanes(VectorOperators.ADD);
        }
        for (; i < a.length; i++) {
            total += Long.bitCount(a[i]);
        }
        return total;
    }
}
