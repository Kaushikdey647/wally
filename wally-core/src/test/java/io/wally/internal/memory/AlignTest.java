package io.wally.internal.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlignTest {

    @Test
    void alignsUpToPowerOfTwo() {
        assertEquals(0, Align.align(0, 8));
        assertEquals(8, Align.align(1, 8));
        assertEquals(8, Align.align(8, 8));
        assertEquals(16, Align.align(9, 8));
        assertEquals(1L << 32, Align.align((1L << 32) - 3, 8));
    }

    @Test
    void detectsAlignmentAndPowersOfTwo() {
        assertTrue(Align.isAligned(64, 8));
        assertFalse(Align.isAligned(65, 8));
        assertTrue(Align.isPowerOfTwo(1L << 30));
        assertFalse(Align.isPowerOfTwo(0));
        assertFalse(Align.isPowerOfTwo(96));
    }
}
