package et.elisa.iwf.bitops;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.random.RandomGenerator;

import org.junit.jupiter.api.Test;

class SimdBitOpsTest {

    private long[] randomLongs(RandomGenerator rnd, int len) {
        long[] a = new long[len];
        for (int i = 0; i < len; i++) {
            a[i] = rnd.nextLong();
        }
        return a;
    }

    private byte[] randomBytes(RandomGenerator rnd, int len) {
        byte[] a = new byte[len];
        rnd.nextBytes(a);
        return a;
    }

    @Test
    void andMatchesScalar() {
        var rnd = RandomGenerator.of("L64X128MixRandom");
        for (int len : new int[] {0, 1, 7, 64}) {
            long[] a = randomLongs(rnd, len);
            long[] b = randomLongs(rnd, len);
            assertArrayEquals(BitOps.and(a, b), SimdBitOps.and(a, b), "AND len=" + len);
        }
    }

    @Test
    void orMatchesScalar() {
        var rnd = RandomGenerator.of("L64X128MixRandom");
        for (int len : new int[] {0, 1, 7, 64}) {
            long[] a = randomLongs(rnd, len);
            long[] b = randomLongs(rnd, len);
            assertArrayEquals(BitOps.or(a, b), SimdBitOps.or(a, b), "OR len=" + len);
        }
    }

    @Test
    void xorMatchesScalar() {
        var rnd = RandomGenerator.of("L64X128MixRandom");
        for (int len : new int[] {0, 1, 7, 64}) {
            long[] a = randomLongs(rnd, len);
            long[] b = randomLongs(rnd, len);
            assertArrayEquals(BitOps.xor(a, b), SimdBitOps.xor(a, b), "XOR len=" + len);
        }
    }

    @Test
    void notMatchesScalar() {
        var rnd = RandomGenerator.of("L64X128MixRandom");
        for (int len : new int[] {0, 1, 7, 64}) {
            long[] a = randomLongs(rnd, len);
            assertArrayEquals(BitOps.not(a), SimdBitOps.not(a), "NOT len=" + len);
        }
    }

    @Test
    void xorBytesMatchesScalar() {
        var rnd = RandomGenerator.of("L64X128MixRandom");
        for (int len : new int[] {0, 1, 7, 64}) {
            byte[] a = randomBytes(rnd, len);
            byte[] b = randomBytes(rnd, len);
            assertArrayEquals(BitOps.xorBytes(a, b), SimdBitOps.xorBytes(a, b),
                    "XOR bytes len=" + len);
        }
    }

    @Test
    void nibbleSwapMatchesScalar() {
        var rnd = RandomGenerator.of("L64X128MixRandom");
        for (int len : new int[] {0, 1, 7, 64}) {
            byte[] a = randomBytes(rnd, len);
            assertArrayEquals(BitOps.nibbleSwap(a), SimdBitOps.nibbleSwap(a),
                    "nibbleSwap len=" + len);
        }
    }

    @Test
    void popcountMatchesScalar() {
        var rnd = RandomGenerator.of("L64X128MixRandom");
        for (int len : new int[] {0, 1, 7, 64}) {
            long[] a = randomLongs(rnd, len);
            assertEquals(BitOps.popcount(a), SimdBitOps.popcount(a),
                    "popcount len=" + len);
        }
    }
}
