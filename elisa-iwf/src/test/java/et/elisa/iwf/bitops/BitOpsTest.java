package et.elisa.iwf.bitops;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.random.RandomGenerator;

import org.junit.jupiter.api.Test;

/**
 * SIMD results must equal the scalar BitOps truth for every length,
 * including tails that do not fill a whole vector lane group.
 */
class BitOpsTest {

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
    void simdMatchesScalarForAllBitwiseOps() {
        var rnd = RandomGenerator.of("L64X128MixRandom");
        for (int len : new int[] {1, 2, 3, 7, 8, 9, 31, 64, 65, 1000}) {
            long[] a = randomLongs(rnd, len);
            long[] b = randomLongs(rnd, len);
            assertArrayEquals(BitOps.and(a, b), SimdBitOps.and(a, b), "AND len=" + len);
            assertArrayEquals(BitOps.or(a, b), SimdBitOps.or(a, b), "OR len=" + len);
            assertArrayEquals(BitOps.xor(a, b), SimdBitOps.xor(a, b), "XOR len=" + len);
            assertArrayEquals(BitOps.not(a), SimdBitOps.not(a), "NOT len=" + len);

            byte[] x = randomBytes(rnd, len * 8);
            byte[] y = randomBytes(rnd, len * 8);
            assertArrayEquals(BitOps.xorBytes(x, y), SimdBitOps.xorBytes(x, y),
                    "XOR bytes len=" + len * 8);
            assertArrayEquals(BitOps.nibbleSwap(x), SimdBitOps.nibbleSwap(x),
                    "nibbleSwap len=" + len * 8);
        }
    }

    @Test
    void popcountAgreesWithScalar() {
        var rnd = RandomGenerator.of("L64X128MixRandom");
        long[] a = randomLongs(rnd, 257);
        assertEquals(BitOps.popcount(a), SimdBitOps.popcount(a));
        assertTrue(SimdBitOps.popcount(new long[] {0L, -1L, 5L}) == 0 + 64 + 2);
    }

    @Test
    void nibbleSwapRoundTripsTbcdDigits() {
        // 0x54 = digits 4,5 swapped -> 0x45
        assertArrayEquals(new byte[] {0x45}, SimdBitOps.nibbleSwap(new byte[] {0x54}));
        assertArrayEquals(new byte[] {0x12, (byte) 0xF0},
                SimdBitOps.nibbleSwap(new byte[] {0x21, 0x0F}));
    }

    @Test
    void simdSpeciesAvailable() {
        assertTrue(SimdBitOps.speciesLong().contains("Species"),
                "vector species must resolve: " + SimdBitOps.speciesLong());
    }
}
